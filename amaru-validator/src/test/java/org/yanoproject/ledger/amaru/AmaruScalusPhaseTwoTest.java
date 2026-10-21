package org.yanoproject.ledger.amaru;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code amaru-scalus} engine on the mutation world: the phase-2 contract the
 * {@link ScalusScriptPhaseEvaluator} carries, and what it does with a transaction Haskell's decoder rejects.
 */
class AmaruScalusPhaseTwoTest {

    private final AmaruTransactionValidator engine = new AmaruTransactionValidator(
            LedgerValidationEngines.AMARU_SCALUS, AmaruEngineConfig.defaults(2),
            ScenarioSupport.network(MutationWorld.network()), new ScalusScriptPhaseEvaluator(),
            AmaruLedgerConstants.HASKELL, ScenarioSupport.wasm());

    @AfterEach
    void close() {
        engine.close();
    }

    /**
     * A PlutusV2 script of Plutus Core 1.1.0 runs only from protocol version 11 ({@code plcVersionsAvailableIn},
     * Versions.hs:341-357): the evaluator fails it before, so a transaction claiming it valid is
     * {@code ValidationTagMismatch}.
     */
    @Test
    void aPlutusCore110V2ScriptFailsBeforeProtocolVersion11() {
        TxValidationOutcome pv10 = validate(ConwayTxBuilder.build(MutationWorld.plutusCore110Spec(),
                MutationWorld.view(10)).cbor(), 10);
        assertThat(pv10).isInstanceOfSatisfying(TxValidationOutcome.Invalid.class, invalid -> {
            assertThat(invalid.failures()).extracting(LedgerFailure::qualifiedName)
                    .containsExactly("UTXOS.ValidationTagMismatch");
            assertThat(invalid.failures().getFirst().detail()).startsWith("FailedUnexpectedly");
        });

        TxValidationOutcome pv11 = validate(ConwayTxBuilder.build(MutationWorld.plutusCore110Spec(),
                MutationWorld.view(11)).cbor(), 11);
        assertThat(pv11).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    /**
     * Amaru decodes a witness set with key 8; Haskell's decoder does not ({@code AlonzoTxWits}: keys 0-7), and neither
     * does {@code RawTransaction}, which the effects are derived from: a decoding failure, not an engine failure.
     */
    @Test
    void aTransactionHaskellCannotDecodeIsADecodingFailure() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.feeAdjust = BigInteger.valueOf(10_000); // for the entry's two bytes
        byte[] tx = MutationWorld.withWitnessEntry(ConwayTxBuilder.build(spec, MutationWorld.view(10)).cbor(), "0801");

        assertThat(validate(tx, 10)).isInstanceOfSatisfying(TxValidationOutcome.Invalid.class, invalid -> {
            assertThat(invalid.failures()).extracting(LedgerFailure::qualifiedName)
                    .containsExactly("ENGINE." + AmaruTransactionValidator.DECODING_FAILURE);
            assertThat(invalid.failures().getFirst().detail()).contains("Amaru accepted")
                    .contains("unknown witness set key 8");
        });
    }

    private TxValidationOutcome validate(byte[] tx, int protocolMajor) {
        InMemoryLedgerView world = MutationWorld.view(protocolMajor);
        return engine.validate(new TxValidationRequest(tx, world, MutationWorld.env(protocolMajor),
                TxValidationRequest.Rule.MEMPOOL, TxValidationRequest.Origin.LOCAL, null));
    }
}
