package org.yanoproject.ledger.scripteval.phase2;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.conway.tx.StrictCbor;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The julc {@code ScriptPhaseEvaluator} behind the Java engine ({@code java-julc}, ADR-056 Phase 7c) on the mutation
 * world's Plutus transactions: the same verdicts and {@code UTXOS} failures the Scalus evaluator gives there
 * ({@code java-scalus}: {@code JavaEngineScriptPreparationTest}; both: {@code ShadowSyncBothEvaluatorsTest}).
 */
class JulcScriptPhaseEvaluatorTest {

    private static final JulcScriptPhaseEvaluator EVALUATOR = new JulcScriptPhaseEvaluator(
            ForecastHorizon.of(() -> 129_600, new EpochSlotCalc(432_000, 432_000, 0)));
    private static final JavaLedgerValidationEngine ENGINE = new JavaLedgerValidationEngine(EVALUATOR);

    @Test
    void anAlwaysSucceedingPlutusV3SpendIsValid() {
        TxValidationOutcome outcome = validate(MutationWorld.scriptSpec());
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Valid.class);
        assertThat(ENGINE.name()).isEqualTo("java-julc");
    }

    /** A spend of a datum-hash output with the datum in the witness set (the V3 {@code SpendingScript} datum). */
    @Test
    void aDatumHashSpendWithItsWitnessDatumIsValid() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.set(1, MutationWorld.DATUM_SCRIPT_INPUT);
        spec.datums.add(MutationWorld.DATUM);
        assertThat(validate(spec)).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    /** A failing script claimed valid is the {@code ValidationTagMismatch} disagreement; claimed invalid, it agrees. */
    @Test
    void anAlwaysFailingScriptFailsPhaseTwo() {
        TxValidationOutcome claimedValid = validate(failingSpec(true));
        assertThat(claimedValid).isInstanceOf(TxValidationOutcome.Invalid.class);
        LedgerFailure mismatch = ((TxValidationOutcome.Invalid) claimedValid).failures().getFirst();
        assertThat(mismatch.qualifiedName()).isEqualTo("UTXOS.ValidationTagMismatch");
        assertThat(mismatch.detail()).contains("FailedUnexpectedly");

        TxValidationOutcome claimedInvalid = validate(failingSpec(false));
        assertThat(claimedInvalid).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    /** The redeemer's declared ExUnits are the budget: one memory unit is not enough. */
    @Test
    void theDeclaredExUnitsAreTheBudget() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.redeemers.clear();
        spec.redeemers.add(MutationWorld.spendRedeemer(1));
        TxValidationOutcome outcome = validate(spec);
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Invalid.class);
        assertThat(((TxValidationOutcome.Invalid) outcome).failures().getFirst().detail()).contains("budget");
    }

    /** {@code UTXOW.MissingRedeemers}, then {@code UTXOS.CollectErrors [NoRedeemer]} (Evaluate.hs:151-155). */
    @Test
    void aPlutusSpendWithoutARedeemerIsNoRedeemer() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.redeemers.clear();
        List<LedgerFailure> failures = ((TxValidationOutcome.Invalid) validate(spec)).failures();
        assertThat(failures).extracting(LedgerFailure::qualifiedName).containsExactly("UTXOW.MissingRedeemers",
                "UTXOS.CollectErrors");
        assertThat(failures.get(1).detail()).contains("NoRedeemer spend[1]");
    }

    /** A validity bound at or past the forecast horizon: {@code BadTranslation TimeTranslationPastHorizon}. */
    @Test
    void aValidityBoundPastTheForecastHorizonIsCollectErrors() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.ttl = 432_000L;
        LedgerFailure first = ((TxValidationOutcome.Invalid) validate(spec)).failures().getFirst();
        assertThat(first.qualifiedName()).isEqualTo("UTXOS.CollectErrors");
        assertThat(first.detail()).contains("TimeTranslationPastHorizon");

        TxSpec inside = MutationWorld.scriptSpec();
        inside.ttl = 431_999L;
        assertThat(validate(inside)).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    /**
     * A PlutusV2 script of Plutus Core 1.1.0 runs only from protocol version 11 ({@code plcVersionsAvailableIn},
     * Versions.hs:341-357): before, the evaluator fails it without running it ({@code mkTermToEvaluate},
     * Common/Eval.hs:113-122), a phase-2 failure.
     */
    @Test
    void aPlutusCore110V2ScriptFailsBeforeProtocolVersion11() {
        TxValidationOutcome pv10 = validate(MutationWorld.plutusCore110Spec(), 10);
        assertThat(pv10).isInstanceOf(TxValidationOutcome.Invalid.class);
        LedgerFailure mismatch = ((TxValidationOutcome.Invalid) pv10).failures().getFirst();
        assertThat(mismatch.qualifiedName()).isEqualTo("UTXOS.ValidationTagMismatch");
        assertThat(mismatch.detail()).contains("FailedUnexpectedly")
                .contains("PlutusCoreLanguageNotAvailableError 1.1.0 PlutusV2 protocol version 10");

        TxSpec claimedInvalid = MutationWorld.plutusCore110Spec();
        claimedInvalid.isValid = false;
        assertThat(validate(claimedInvalid, 10)).isInstanceOf(TxValidationOutcome.Valid.class);

        assertThat(validate(MutationWorld.plutusCore110Spec(), 11)).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    /**
     * {@code isValidPlutusScript} with julc's decoder: malformed flat and V3 trailing bytes are not well formed. Builtin
     * availability is not julc's to judge ({@code PlutusScriptDecoder}, which {@code UTXOW} runs first).
     */
    @Test
    void judgesScriptWellFormedness() {
        assertThat(EVALUATOR.isWellFormed(3, plutusBinary(MutationWorld.ALWAYS_SUCCEEDS.getCborHex()), 10)).isTrue();
        assertThat(EVALUATOR.isWellFormed(2, plutusBinary(MutationWorld.ALWAYS_SUCCEEDS_V2.getCborHex()), 10))
                .isTrue();
        assertThat(EVALUATOR.isWellFormed(3, plutusBinary(MutationWorld.MALFORMED_SCRIPT.getCborHex()), 10)).isFalse();
        assertThat(EVALUATOR.isWellFormed(3, plutusBinary(MutationWorld.TRAILING_BYTES_SCRIPT.getCborHex()), 10))
                .isFalse();
        assertThat(EVALUATOR.isWellFormed(3, plutusBinary(MutationWorld.UNAVAILABLE_BUILTIN_SCRIPT.getCborHex()), 10))
                .isTrue();
        assertThat(EVALUATOR.translatesBootstrapPhaseCertificateDeposits()).isTrue();
    }

    private static TxSpec failingSpec(boolean isValid) {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.remove(MutationWorld.SCRIPT_INPUT);
        spec.inputs.add(MutationWorld.FAIL_SCRIPT_INPUT);
        spec.plutusScripts.clear();
        spec.plutusScripts.add(MutationWorld.ALWAYS_FAILS);
        spec.isValid = isValid;
        return spec;
    }

    /** The {@code PlutusBinary}: the contents of a CCL script's {@code cborHex} byte string. */
    static byte[] plutusBinary(String cborHex) {
        return StrictCbor.bytes(StrictCbor.span(HexUtil.decodeHexString(cborHex)));
    }

    static TxValidationOutcome validate(TxSpec spec) {
        return validate(spec, 10);
    }

    static TxValidationOutcome validate(TxSpec spec, int protocolMajor) {
        byte[] cbor = ConwayTxBuilder.build(spec, MutationWorld.view(protocolMajor)).cbor();
        return ENGINE.validate(new TxValidationRequest(cbor, MutationWorld.view(protocolMajor),
                MutationWorld.env(protocolMajor), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC,
                null));
    }
}
