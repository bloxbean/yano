package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.InfoAction;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 §5 / ADR-057 deviation 9: the Scalus {@code ScriptPhaseEvaluator} on real Plutus transactions from
 * Amaru's Haskell-checked corpus (skipped without {@code AMARU_SCENARIOS_DIR}), and its {@code CollectErrors}
 * translation checks on hand-built bodies.
 */
class ScalusScriptPhaseEvaluatorTest {

    private final ScalusScriptPhaseEvaluator evaluator = new ScalusScriptPhaseEvaluator();

    // ------------------------------------------------------------------ Amaru corpus

    @Test
    void plutusV3SpendPasses() {
        ScriptPhaseResult result = run(scenario("00019"));

        assertThat(result).isInstanceOf(ScriptPhaseResult.Passed.class);
        assertThat(((ScriptPhaseResult.Passed) result).scripts()).singleElement()
                .satisfies(s -> {
                    assertThat(s.purpose()).isEqualTo("spend");
                    assertThat(s.success()).isTrue();
                    assertThat(s.steps()).isPositive();
                });
    }

    @Test
    void plutusV2ReferenceScriptSpendPasses() {
        assertThat(run(scenario("00024"))).isInstanceOf(ScriptPhaseResult.Passed.class);
    }

    @Test
    void failingScriptIsPhaseTwoFailed() {
        ScriptPhaseResult result = run(scenario("00198"));

        assertThat(result).isInstanceOf(ScriptPhaseResult.Failed.class);
        assertThat(((ScriptPhaseResult.Failed) result).scripts()).singleElement()
                .satisfies(s -> assertThat(s.success()).isFalse());
    }

    @Test
    void succeedingScriptInATransactionMarkedInvalidPasses() {
        // The engine turns this into UTXOS.ValidationTagMismatch (PassedUnexpectedly).
        assertThat(run(scenario("00199"))).isInstanceOf(ScriptPhaseResult.Passed.class);
    }

    @Test
    void malformedWitnessScriptIsRejectedBeforeAnyScriptRuns() {
        ScriptPhaseResult result = run(scenario("00256"));

        assertThat(result).isInstanceOf(ScriptPhaseResult.Rejected.class);
        LedgerFailure failure = ((ScriptPhaseResult.Rejected) result).failures().getFirst();
        assertThat(failure.qualifiedName()).isEqualTo("UTXOW.MalformedScriptWitnesses");
        assertThat(failure.phase()).isEqualTo(LedgerFailure.Phase.PHASE_1);
    }

    @Test
    void malformedReferenceScriptOfAnOutputIsRejected() {
        ScriptPhaseResult result = run(scenario("00151"));

        assertThat(result).isInstanceOf(ScriptPhaseResult.Rejected.class);
        assertThat(((ScriptPhaseResult.Rejected) result).failures()).extracting(LedgerFailure::qualifiedName)
                .containsExactly("UTXOW.MalformedReferenceScripts");
    }

    @Test
    void missingCostModelIsCollectErrorsNoCostModel() {
        AmaruScenario scenario = scenario("00019");
        ProtocolParams params = scenario.protocolParams();
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>(params.getCostModelsRaw());
        raw.remove("PlutusV3");
        params.setCostModelsRaw(raw);

        ScriptPhaseResult result = run(scenario, params);

        assertThat(result).isInstanceOf(ScriptPhaseResult.Rejected.class);
        LedgerFailure failure = ((ScriptPhaseResult.Rejected) result).failures().getFirst();
        assertThat(failure.qualifiedName()).isEqualTo("UTXOS.CollectErrors");
        assertThat(failure.phase()).isEqualTo(LedgerFailure.Phase.PHASE_1);
        assertThat(failure.detail()).contains("NoCostModel PlutusV3");
    }

    @Test
    void transactionWithoutPlutusScriptsPassesWithNoScripts() {
        ScriptPhaseResult result = run(scenario("00021"));

        assertThat(result).isInstanceOf(ScriptPhaseResult.Passed.class);
        assertThat(((ScriptPhaseResult.Passed) result).scripts()).isEmpty();
    }

    @Test
    void validityBoundPastTheForecastHorizonIsCollectErrors() throws Exception {
        AmaruScenario scenario = scenario("00019");
        Transaction tx = Transaction.deserialize(scenario.txCbor());
        long slot = scenario.env().currentSlot();
        // Horizon: the first boundary at or after slot + 10 with 100-slot epochs.
        ForecastHorizon horizon = ForecastHorizon.of(() -> 10, new EpochSlotCalc(100, 100, 0));
        long upper = horizon.exclusiveUpperSlot(slot);
        ScalusScriptPhaseEvaluator withHorizon = new ScalusScriptPhaseEvaluator(horizon);

        tx.getBody().setTtl(upper);
        ScriptPhaseResult past = runWith(withHorizon, scenario, tx.serialize(), slot);
        assertThat(past).isInstanceOf(ScriptPhaseResult.Rejected.class);
        LedgerFailure failure = ((ScriptPhaseResult.Rejected) past).failures().getFirst();
        assertThat(failure.qualifiedName()).isEqualTo("UTXOS.CollectErrors");
        assertThat(failure.detail()).contains("TimeTranslationPastHorizon ttl");

        tx.getBody().setTtl(upper - 1);
        assertThat(runWith(withHorizon, scenario, tx.serialize(), slot)).isInstanceOf(ScriptPhaseResult.Passed.class);
    }

    @Test
    void horizonChecksBothBounds() {
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(SHELLEY)));
        tx.getBody().setValidityStartInterval(500);
        assertThat(ScalusScriptPhaseEvaluator.horizonError(tx, 500))
                .hasValueSatisfying(e -> assertThat(e).startsWith("TimeTranslationPastHorizon validity start 500"));
        assertThat(ScalusScriptPhaseEvaluator.horizonError(tx, 501)).isEmpty();
        tx.getBody().setTtl(501);
        assertThat(ScalusScriptPhaseEvaluator.horizonError(tx, 501))
                .hasValueSatisfying(e -> assertThat(e).startsWith("TimeTranslationPastHorizon ttl 501"));
        assertThat(ScalusScriptPhaseEvaluator.horizonError(tx, 502)).isEmpty();
    }

    // ------------------------------------------------------------------ BadTranslation (no corpus needed)

    private static final String A = "aa".repeat(32);
    private static final String B = "bb".repeat(32);
    private static final String SHELLEY = new Address(HexUtil.decodeHexString("60" + "22".repeat(28))).toBech32();
    private static final String BYRON = "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

    @Test
    void plutusV3WithOverlappingReferenceInputsIsABadTranslationFromProtocolVersion11() {
        Transaction tx = tx(List.of(input(A, 0)), List.of(input(A, 0)), List.of(out(SHELLEY)));

        assertThat(ScalusScriptPhaseEvaluator.translationError(tx, Map.of(), 11, 3))
                .hasValueSatisfying(e -> assertThat(e).startsWith("ReferenceInputsNotDisjointFromInputs"));
        // At PV 10 the UTXO rule's BabbageNonDisjointRefInputs reports it instead (phase one).
        assertThat(ScalusScriptPhaseEvaluator.translationError(tx, Map.of(), 10, 3)).isEmpty();
        assertThat(ScalusScriptPhaseEvaluator.translationError(
                tx(List.of(input(A, 0)), List.of(input(B, 0)), List.of(out(SHELLEY))), Map.of(), 11, 3)).isEmpty();
    }

    @Test
    void plutusV1AndV2CannotSeeConwayFeatures() {
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(SHELLEY)));
        tx.getBody().setProposalProcedures(List.of(ProposalProcedure.builder().deposit(BigInteger.ONE)
                .rewardAccount("e0" + "11".repeat(28)).govAction(new InfoAction()).build()));

        assertThat(ScalusScriptPhaseEvaluator.translationError(tx, Map.of(), 10, 2))
                .contains("ProposalProceduresFieldNotSupported");
        assertThat(ScalusScriptPhaseEvaluator.translationError(tx, Map.of(), 10, 3)).isEmpty();
        tx.getBody().setProposalProcedures(null);
        tx.getBody().setDonation(BigInteger.TEN);
        assertThat(ScalusScriptPhaseEvaluator.translationError(tx, Map.of(), 10, 1))
                .contains("TreasuryDonationFieldNotSupported");
    }

    @Test
    void plutusV1CannotSeeInlineDatumsAndNoLanguageSeesByronAddresses() {
        Outpoint spent = Outpoints.normalize(new Outpoint(A, 0));
        TransactionOutput inlineOutput = TransactionOutput.builder().address(SHELLEY)
                .value(Value.builder().coin(BigInteger.TEN).build()).inlineDatum(BigIntPlutusData.of(1)).build();
        Map<Outpoint, UtxoEntry> resolved = Map.of(spent, new UtxoEntry(spent, inlineOutput));
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(SHELLEY)));

        assertThat(ScalusScriptPhaseEvaluator.translationError(tx, resolved, 10, 1))
                .hasValueSatisfying(e -> assertThat(e).startsWith("InlineDatumsNotSupported input"));
        assertThat(ScalusScriptPhaseEvaluator.translationError(tx, resolved, 10, 2)).isEmpty();

        Transaction byron = tx(List.of(input(A, 0)), List.of(), List.of(out(BYRON)));
        for (int language : List.of(1, 2, 3)) {
            assertThat(ScalusScriptPhaseEvaluator.translationError(byron, Map.of(), 10, language))
                    .hasValueSatisfying(e -> assertThat(e).startsWith("ByronTxOutInContext output 0"));
        }
    }

    @Test
    void collectErrorsAccumulatesAcrossScripts() {
        ProtocolParams params = new ProtocolParams();
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
        raw.put("PlutusV2", List.of(1L));
        params.setCostModelsRaw(raw);
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(BYRON)));
        List<NeededPlutusScript> needed = List.of(
                new NeededPlutusScript("spend", 0, "cc".repeat(28), 3, true),
                new NeededPlutusScript("mint", 0, "dd".repeat(28), 2, true),
                new NeededPlutusScript("mint", 1, "ee".repeat(28), 2, false));

        List<String> errors = ScalusScriptPhaseEvaluator.collectErrors(tx, Map.of(), params, 10, needed);

        assertThat(errors).containsExactly("NoCostModel PlutusV3",
                "BadTranslation ByronTxOutInContext output 0 for PlutusV2");
    }

    // ------------------------------------------------------------------ helpers

    private static AmaruScenario scenario(String prefix) {
        AmaruScenarioLoader loader = AmaruScenarioLoader.fromEnvironment();
        Path file = loader.scenarioFiles().stream()
                .filter(p -> p.getFileName().toString().startsWith(prefix + "-"))
                .findFirst().orElseThrow(() -> new IllegalStateException("no scenario " + prefix));
        return loader.load(file);
    }

    private ScriptPhaseResult run(AmaruScenario scenario) {
        return run(scenario, scenario.protocolParams());
    }

    private ScriptPhaseResult runWith(ScalusScriptPhaseEvaluator evaluator, AmaruScenario scenario, byte[] txCbor,
                                      long slot) throws Exception {
        Transaction tx = Transaction.deserialize(txCbor);
        Map<Outpoint, UtxoEntry> resolved = new LinkedHashMap<>();
        for (TransactionInput input : allInputs(tx)) {
            Outpoint outpoint = Outpoints.normalize(new Outpoint(input.getTransactionId(), input.getIndex()));
            scenario.view().utxo(outpoint).orElseThrowUnavailable().ifPresent(e -> resolved.put(outpoint, e));
        }
        return evaluator.evaluate(txCbor, tx, resolved, scenario.protocolParams(), scenario.env().slotConfig(), slot);
    }

    private static List<TransactionInput> allInputs(Transaction tx) {
        List<TransactionInput> all = new ArrayList<>();
        TransactionBody body = tx.getBody();
        for (List<TransactionInput> part : Arrays.asList(body.getInputs(), body.getReferenceInputs(),
                body.getCollateral())) {
            if (part != null) {
                all.addAll(part);
            }
        }
        return all;
    }

    private ScriptPhaseResult run(AmaruScenario scenario, ProtocolParams params) {
        Transaction tx;
        try {
            tx = Transaction.deserialize(scenario.txCbor());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Map<Outpoint, UtxoEntry> resolved = new LinkedHashMap<>();
        TransactionBody body = tx.getBody();
        List<TransactionInput> all = new ArrayList<>();
        for (List<TransactionInput> part : Arrays.asList(body.getInputs(), body.getReferenceInputs(),
                body.getCollateral())) {
            if (part != null) {
                all.addAll(part);
            }
        }
        for (TransactionInput input : all) {
            Outpoint outpoint = Outpoints.normalize(new Outpoint(input.getTransactionId(), input.getIndex()));
            Optional<UtxoEntry> entry = scenario.view().utxo(outpoint).orElseThrowUnavailable();
            entry.ifPresent(e -> resolved.put(outpoint, e));
        }
        return evaluator.evaluate(scenario.txCbor(), tx, resolved, params, scenario.env().slotConfig());
    }

    private static TransactionInput input(String hash, int index) {
        return new TransactionInput(hash, index);
    }

    private static TransactionOutput out(String address) {
        return new TransactionOutput(address, Value.builder().coin(BigInteger.valueOf(2_000_000)).build());
    }

    private static Transaction tx(List<TransactionInput> inputs, List<TransactionInput> references,
                                  List<TransactionOutput> outputs) {
        TransactionBody body = TransactionBody.builder().inputs(new ArrayList<>(inputs))
                .referenceInputs(references.isEmpty() ? null : new ArrayList<>(references))
                .outputs(new ArrayList<>(outputs)).fee(BigInteger.ONE).build();
        return Transaction.builder().body(body).build();
    }
}
