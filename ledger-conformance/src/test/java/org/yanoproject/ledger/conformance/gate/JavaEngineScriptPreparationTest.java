package org.yanoproject.ledger.conformance.gate;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.util.EpochSlotCalc;
import org.yanoproject.ledger.conformance.engines.JavaViewEngine;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java engine with the real Scalus evaluator on {@code UTXOS.CollectErrors} cases the mutation matrix cannot
 * hold as single faults (Haskell also reports a {@code UTXOW} failure for them, which Phase 3b adds).
 */
class JavaEngineScriptPreparationTest {

    private static final ScalusScriptPhaseEvaluator EVALUATOR = new ScalusScriptPhaseEvaluator(
            ForecastHorizon.of(() -> 129_600, new EpochSlotCalc(432_000, 432_000, 0)));

    /** A needed Plutus script without a redeemer: {@code CollectErrors [NoRedeemer]} (Evaluate.hs:151-155). */
    @Test
    @Covers("UTXOS.CollectErrors")
    void aPlutusSpendWithoutARedeemerIsNotAccepted() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.redeemers.clear();
        LedgerFailure first = firstFailure(spec);
        assertThat(first.qualifiedName()).isEqualTo("UTXOS.CollectErrors");
        assertThat(first.detail()).contains("NoRedeemer spend[1]");
    }

    /**
     * A validity bound at or past the forecast horizon (the first epoch boundary at or after {@code slot + 3k/f}:
     * here 432000) cannot be translated for the script context: {@code CollectErrors [BadTranslation
     * TimeTranslationPastHorizon]} (Alonzo/Plutus/TxInfo.hs:252-274; Amaru's "OutsideForecast").
     */
    @Test
    @Covers("UTXOS.CollectErrors")
    void aValidityBoundPastTheForecastHorizonIsCollectErrors() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.ttl = 432_000L;
        LedgerFailure first = firstFailure(spec);
        assertThat(first.qualifiedName()).isEqualTo("UTXOS.CollectErrors");
        assertThat(first.detail()).contains("TimeTranslationPastHorizon");

        TxSpec inside = MutationWorld.scriptSpec();
        inside.ttl = 431_999L;
        assertThat(validate(inside)).isInstanceOf(TxValidationOutcome.Valid.class);
    }

    private static LedgerFailure firstFailure(TxSpec spec) {
        TxValidationOutcome outcome = validate(spec);
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Invalid.class);
        return ((TxValidationOutcome.Invalid) outcome).failures().getFirst();
    }

    private static TxValidationOutcome validate(TxSpec spec) {
        byte[] cbor = ConwayTxBuilder.build(spec, MutationWorld.view()).cbor();
        return JavaViewEngine.create(EVALUATOR).validate(new TxValidationRequest(cbor, MutationWorld.view(),
                MutationWorld.env(), TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, null));
    }
}
