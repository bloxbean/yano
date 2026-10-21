package org.yanoproject.ledger.rules.phase2;

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
import org.yanoproject.ledger.rules.phase2.ScriptCollection.NeededScript;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine-neutral {@code CollectErrors} checks every phase-2 evaluator shares ({@link ScriptCollection}, moved with
 * these tests from {@code ScalusScriptPhaseEvaluatorTest}), on hand-built bodies.
 */
class ScriptCollectionTest {

    private static final String A = "aa".repeat(32);
    private static final String B = "bb".repeat(32);
    private static final String SHELLEY = new Address(HexUtil.decodeHexString("60" + "22".repeat(28))).toBech32();
    private static final String BYRON = "Ae2tdPwUPEZFRbyhz3cpfC2CumGzNkFBN2L42rcUc2yjQpEkxDbkPodpMAi";

    @Test
    void horizonChecksBothBounds() {
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(SHELLEY)));
        tx.getBody().setValidityStartInterval(500);
        assertThat(ScriptCollection.horizonError(tx, 500))
                .hasValueSatisfying(e -> assertThat(e).startsWith("TimeTranslationPastHorizon validity start 500"));
        assertThat(ScriptCollection.horizonError(tx, 501)).isEmpty();
        tx.getBody().setTtl(501);
        assertThat(ScriptCollection.horizonError(tx, 501))
                .hasValueSatisfying(e -> assertThat(e).startsWith("TimeTranslationPastHorizon ttl 501"));
        assertThat(ScriptCollection.horizonError(tx, 502)).isEmpty();
    }

    @Test
    void plutusV3WithOverlappingReferenceInputsIsABadTranslationFromProtocolVersion11() {
        Transaction tx = tx(List.of(input(A, 0)), List.of(input(A, 0)), List.of(out(SHELLEY)));

        assertThat(ScriptCollection.translationError(tx, Map.of(), 11, 3, -1))
                .hasValueSatisfying(e -> assertThat(e).startsWith("ReferenceInputsNotDisjointFromInputs"));
        // At PV 10 the UTXO rule's BabbageNonDisjointRefInputs reports it instead (phase one).
        assertThat(ScriptCollection.translationError(tx, Map.of(), 10, 3, -1)).isEmpty();
        assertThat(ScriptCollection.translationError(
                tx(List.of(input(A, 0)), List.of(input(B, 0)), List.of(out(SHELLEY))), Map.of(), 11, 3, -1)).isEmpty();
    }

    @Test
    void plutusV1AndV2CannotSeeConwayFeatures() {
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(SHELLEY)));
        tx.getBody().setProposalProcedures(List.of(ProposalProcedure.builder().deposit(BigInteger.ONE)
                .rewardAccount("e0" + "11".repeat(28)).govAction(new InfoAction()).build()));

        assertThat(ScriptCollection.translationError(tx, Map.of(), 10, 2, -1))
                .contains("ProposalProceduresFieldNotSupported");
        assertThat(ScriptCollection.translationError(tx, Map.of(), 10, 3, -1)).isEmpty();
        tx.getBody().setProposalProcedures(null);
        tx.getBody().setDonation(BigInteger.TEN);
        assertThat(ScriptCollection.translationError(tx, Map.of(), 10, 1, -1))
                .contains("TreasuryDonationFieldNotSupported");
    }

    @Test
    void plutusV1CannotSeeInlineDatumsAndNoLanguageSeesByronAddresses() {
        Outpoint spent = Outpoints.normalize(new Outpoint(A, 0));
        TransactionOutput inlineOutput = TransactionOutput.builder().address(SHELLEY)
                .value(Value.builder().coin(BigInteger.TEN).build()).inlineDatum(BigIntPlutusData.of(1)).build();
        Map<Outpoint, UtxoEntry> resolved = Map.of(spent, new UtxoEntry(spent, inlineOutput));
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(SHELLEY)));

        assertThat(ScriptCollection.translationError(tx, resolved, 10, 1, -1))
                .hasValueSatisfying(e -> assertThat(e).startsWith("InlineDatumsNotSupported input"));
        assertThat(ScriptCollection.translationError(tx, resolved, 10, 2, -1)).isEmpty();

        Transaction byron = tx(List.of(input(A, 0)), List.of(), List.of(out(BYRON)));
        for (int language : List.of(1, 2, 3)) {
            assertThat(ScriptCollection.translationError(byron, Map.of(), 10, language, -1))
                    .hasValueSatisfying(e -> assertThat(e).startsWith("ByronTxOutInContext output 0"));
        }
    }

    /**
     * The TxInfo translates the inputs as a {@code Set TxIn} ({@code Set.toList}: transaction id bytes, then index), so
     * the first failure named is the first in that order, not in the body's.
     */
    @Test
    void translationFailuresNameTheFirstInputInSetOrder() {
        Outpoint inline = Outpoints.normalize(new Outpoint(A, 1));
        Outpoint byron = Outpoints.normalize(new Outpoint(B, 0));
        Outpoint byronFirst = Outpoints.normalize(new Outpoint(A, 0));
        TransactionOutput inlineOutput = TransactionOutput.builder().address(SHELLEY)
                .value(Value.builder().coin(BigInteger.TEN).build()).inlineDatum(BigIntPlutusData.of(1)).build();
        Map<Outpoint, UtxoEntry> resolved = Map.of(inline, new UtxoEntry(inline, inlineOutput),
                byron, new UtxoEntry(byron, out(BYRON)), byronFirst, new UtxoEntry(byronFirst, out(BYRON)));

        // body order B#0, A#1: the set order reads A#1 first
        Transaction tx = tx(List.of(input(B, 0), input(A, 1)), List.of(), List.of(out(SHELLEY)));
        assertThat(ScriptCollection.translationError(tx, resolved, 10, 1, -1))
                .hasValue("InlineDatumsNotSupported input " + input(A, 1));
        // body order A#1, A#0: the same transaction id, then the index
        Transaction byIndex = tx(List.of(input(A, 1), input(A, 0)), List.of(), List.of(out(SHELLEY)));
        assertThat(ScriptCollection.translationError(byIndex, resolved, 10, 1, -1))
                .hasValue("ByronTxOutInContext input " + input(A, 0));
        // reference inputs too
        Transaction references = tx(List.of(), List.of(input(B, 0), input(A, 1)), List.of(out(SHELLEY)));
        assertThat(ScriptCollection.translationError(references, resolved, 10, 1, -1))
                .hasValue("InlineDatumsNotSupported reference input " + input(A, 1));
    }

    @Test
    void conwayPlutusV1AcceptsReferenceScriptsInEveryTxOut() {
        // Conway's own transTxOutV1 / transTxInInfoV1 (Conway/TxInfo.hs:306-335, reference inputs at :411) reject an
        // inline datum and a Byron address, not a reference script: only Babbage's (Babbage/TxInfo.hs:119-121) did,
        // and Babbage/Imp/UtxosSpec.hs:71-95 expects "PlutusV1 with references" to succeed after Babbage.
        byte[] script = HexUtil.decodeHexString("8201" + "4746010000222499");
        TransactionOutput withScript = TransactionOutput.builder().address(SHELLEY)
                .value(Value.builder().coin(BigInteger.TEN).build()).scriptRef(script).build();
        Outpoint spent = Outpoints.normalize(new Outpoint(A, 0));
        Outpoint referenced = Outpoints.normalize(new Outpoint(B, 0));
        Map<Outpoint, UtxoEntry> resolved = Map.of(spent, new UtxoEntry(spent, withScript),
                referenced, new UtxoEntry(referenced, withScript));
        Transaction tx = tx(List.of(input(A, 0)), List.of(input(B, 0)), List.of(withScript));

        for (int language : List.of(1, 2, 3)) {
            assertThat(ScriptCollection.translationError(tx, resolved, 10, language, -1)).isEmpty();
        }
        // The inline-datum restriction stays, for reference inputs too.
        TransactionOutput inline = TransactionOutput.builder().address(SHELLEY)
                .value(Value.builder().coin(BigInteger.TEN).build()).inlineDatum(BigIntPlutusData.of(1)).build();
        assertThat(ScriptCollection.translationError(tx,
                Map.of(spent, new UtxoEntry(spent, withScript), referenced, new UtxoEntry(referenced, inline)), 10, 1,
                -1))
                .hasValueSatisfying(e -> assertThat(e).startsWith("InlineDatumsNotSupported reference input"));
    }

    @Test
    void translationFailuresFollowConwaysTxInfoOrder() {
        // V3 at PV 11: inputs, reference inputs, then the disjointness check, then outputs (Conway/TxInfo.hs:495-501).
        Transaction overlapping = tx(List.of(input(A, 0)), List.of(input(A, 0)), List.of(out(BYRON)));
        assertThat(ScriptCollection.translationError(overlapping, Map.of(), 11, 3, -1))
                .hasValueSatisfying(e -> assertThat(e).startsWith("ReferenceInputsNotDisjointFromInputs"));
        // The validity interval comes before the inputs (after the V1/V2 feature guard).
        Transaction pastHorizon = tx(List.of(input(A, 0)), List.of(), List.of(out(BYRON)));
        pastHorizon.getBody().setTtl(600);
        assertThat(ScriptCollection.translationError(pastHorizon, Map.of(), 10, 3, 500))
                .hasValueSatisfying(e -> assertThat(e).startsWith("TimeTranslationPastHorizon ttl 600"));
        pastHorizon.getBody().setDonation(BigInteger.ONE);
        assertThat(ScriptCollection.translationError(pastHorizon, Map.of(), 10, 2, 500))
                .contains("TreasuryDonationFieldNotSupported");
        // No TxInfo is built for a language without a cost model, so no horizon failure either.
        ProtocolParams noCostModels = new ProtocolParams();
        noCostModels.setCostModelsRaw(new LinkedHashMap<>());
        assertThat(ScriptCollection.collectErrors(pastHorizon, Map.of(), noCostModels, 10,
                List.of(new NeededScript("spend", 0, "cc".repeat(28), 3, true, new byte[0])), 500))
                .containsExactly("NoCostModel PlutusV3");
    }

    /** {@code plcVersionsAvailableIn} (Common/Versions.hs:341-357): exactly 1.0.0, and 1.1.0 for V3 or from PV 11. */
    @Test
    void plutusCoreVersionsFollowPlcVersionsAvailableIn() {
        byte[] v100 = HexUtil.decodeHexString("46010000222499");
        byte[] v110 = HexUtil.decodeHexString("46010100222499");
        byte[] v101 = HexUtil.decodeHexString("46010001222499");
        for (int language : List.of(1, 2, 3)) {
            assertThat(ScriptCollection.plutusCoreVersionError(language, v100, 9)).isEmpty();
            assertThat(ScriptCollection.plutusCoreVersionError(language, v110, 11)).isEmpty();
            assertThat(ScriptCollection.plutusCoreVersionError(language, v101, 11))
                    .hasValueSatisfying(e -> assertThat(e).startsWith("PlutusCoreLanguageNotAvailableError 1.0.1"));
        }
        assertThat(ScriptCollection.plutusCoreVersionError(3, v110, 9)).isEmpty();
        assertThat(ScriptCollection.plutusCoreVersionError(1, v110, 10)).hasValue(
                "PlutusCoreLanguageNotAvailableError 1.1.0 PlutusV1 protocol version 10");
        assertThat(ScriptCollection.plutusCoreVersionError(2, v110, 10)).isPresent();

        // Every evaluator's preparation: a failed outcome per script its language cannot run.
        List<NeededScript> needed = List.of(new NeededScript("cert", 0, "cc".repeat(28), 2, true, v110),
                new NeededScript("mint", 0, "dd".repeat(28), 3, true, v110));
        assertThat(ScriptCollection.plutusCoreVersionFailures(needed, 10)).singleElement().satisfies(o -> {
            assertThat(o.success()).isFalse();
            assertThat(List.of(o.purpose(), o.index())).containsExactly("cert", 0);
            assertThat(o.error()).contains("PlutusCoreLanguageNotAvailableError 1.1.0 PlutusV2 protocol version 10");
        });
        assertThat(ScriptCollection.plutusCoreVersionFailures(needed, 11)).isEmpty();
    }

    @Test
    void collectErrorsAccumulatesAcrossScripts() {
        ProtocolParams params = new ProtocolParams();
        LinkedHashMap<String, List<Long>> raw = new LinkedHashMap<>();
        raw.put("PlutusV2", List.of(1L));
        params.setCostModelsRaw(raw);
        Transaction tx = tx(List.of(input(A, 0)), List.of(), List.of(out(BYRON)));
        List<NeededScript> needed = List.of(
                new NeededScript("spend", 0, "cc".repeat(28), 3, true, new byte[0]),
                new NeededScript("mint", 0, "dd".repeat(28), 2, true, new byte[0]),
                new NeededScript("mint", 1, "ee".repeat(28), 2, false, new byte[0]));

        List<String> errors = ScriptCollection.collectErrors(tx, Map.of(), params, 10, needed, -1);

        // A needed script without a redeemer is NoRedeemer (Alonzo/Plutus/Evaluate.hs:151-155).
        assertThat(errors).containsExactly("NoCostModel PlutusV3", "NoRedeemer mint[1]",
                "BadTranslation ByronTxOutInContext output 0 for PlutusV2");
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
