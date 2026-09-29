package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

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
 * Amaru's Haskell-checked corpus (skipped without {@code AMARU_SCENARIOS_DIR}). The engine-neutral
 * {@code CollectErrors} translation checks are {@code ScriptCollectionTest}'s (ledger-rules).
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

    /**
     * The Java engine's {@code UTXOW} judges well-formedness itself (ADR-056 Phase 3b): {@code collect} leaves the
     * malformed-script failures out, and {@code isWellFormed} gives the judgement per script.
     */
    @Test
    void collectLeavesMalformedScriptsToTheEngineWhichAsksIsWellFormed() throws Exception {
        AmaruScenario scenario = scenario("00256");
        Transaction tx = Transaction.deserialize(scenario.txCbor());
        Map<Outpoint, UtxoEntry> resolved = new LinkedHashMap<>();
        for (TransactionInput input : allInputs(tx)) {
            Outpoint outpoint = Outpoints.normalize(new Outpoint(input.getTransactionId(), input.getIndex()));
            scenario.view().utxo(outpoint).orElseThrowUnavailable().ifPresent(e -> resolved.put(outpoint, e));
        }
        List<LedgerFailure> collected = evaluator.collect(scenario.txCbor(), tx, resolved, scenario.protocolParams(),
                scenario.env().slotConfig(), -1);
        assertThat(collected).noneMatch(f -> f.qualifiedName().startsWith("UTXOW."));

        RawScript malformed = RawTransaction.parse(scenario.txCbor(), tx).witnessScripts().stream()
                .filter(RawScript::isPlutus).findFirst().orElseThrow();
        assertThat(evaluator.isWellFormed(malformed.language(), malformed.bytes(), 10)).isFalse();
        // (program 1.1.0 (lam ctx (con unit ()))) is a well-formed PlutusV3 program
        byte[] alwaysSucceeds = HexUtil.decodeHexString("450101002499");
        assertThat(evaluator.isWellFormed(3, alwaysSucceeds, 10)).isTrue();
        assertThat(evaluator.isWellFormed(3, HexUtil.decodeHexString("01020304"), 10)).isFalse();
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
    void aWideValiditySlotFailsClosedBeforeScalusDecodes() throws Exception {
        // [{0: [input], 1: [], 2: 0, 3: 2^63}, {}, true, null]: a ttl Haskell decodes as a Word64 SlotNo. A script sees
        // it as a POSIX time, which a placeholder cannot restore, so the evaluator refuses (WideIntegers).
        String input = "81825820" + "00".repeat(32) + "00";
        byte[] wideTtl = HexUtil.decodeHexString("84a400" + input + "0180020003" + "1b8000000000000000" + "a0f5f6");
        ProtocolParams params = new ProtocolParams();
        params.setProtocolMajorVer(10);
        params.setProtocolMinorVer(0);
        List<LedgerFailure> failures = evaluator.collect(wideTtl, new Transaction(), Map.of(), params,
                new SlotConfig(1000, 0, 0), -1);
        assertThat(failures).singleElement().satisfies(f -> {
            assertThat(f.rule().name()).isEqualTo("ENGINE");
            assertThat(f.constructor()).isEqualTo(ScalusScriptPhaseEvaluator.INTEGER_OUT_OF_EVALUATOR_RANGE);
            assertThat(f.detail()).contains("validity interval slot 9223372036854775808");
        });
        // The same transaction with a wide fee instead decodes: the fee is narrowed and restored in any context.
        byte[] wideFee = HexUtil.decodeHexString("84a300" + input + "018002" + "1b8000000000000000" + "a0f5f6");
        assertThat(ScalusPhaseTwo.prepare(wideFee, List.of(), 10, 0).isRight()).isTrue();
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
}
