package org.yanoproject.ledger.rules;

import org.junit.jupiter.api.Test;

import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.TxValidationRequest.Rule;
import org.yanoproject.ledger.rules.effects.TxEffects;
import org.yanoproject.ledger.rules.view.Fixtures;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationApiTest {

    @Test
    void ledgerFailureMapsToLegacyValidationError() {
        LedgerFailure failure = new LedgerFailure(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG",
                LedgerFailure.Phase.PHASE_1, "key:abcd");

        ValidationError error = failure.toValidationError();

        assertThat(error.rule()).isEqualTo("StakeKeyNotRegisteredDELEG");
        assertThat(error.message()).isEqualTo("DELEG.StakeKeyNotRegisteredDELEG: key:abcd");
        assertThat(error.phase()).isEqualTo(ValidationError.Phase.PHASE_1);

        ValidationError phase2 = new LedgerFailure(LedgerRuleName.UTXOS, "ValidationTagMismatch",
                LedgerFailure.Phase.PHASE_2, null).toValidationError();
        assertThat(phase2.phase()).isEqualTo(ValidationError.Phase.PHASE_2);
        assertThat(phase2.message()).isEqualTo("UTXOS.ValidationTagMismatch");
    }

    @Test
    void engineFailureFactories() {
        assertThat(LedgerFailure.ledgerStateUnavailable("store down"))
                .isEqualTo(new LedgerFailure(LedgerRuleName.ENGINE, "LedgerStateUnavailable",
                        LedgerFailure.Phase.PHASE_1, "store down"));
        assertThat(LedgerFailure.eraNotSupported("pv 9").constructor()).isEqualTo("EraNotSupported");
        assertThat(LedgerFailure.phase2InvalidTxNotSupported("").qualifiedName())
                .isEqualTo("ENGINE.Phase2InvalidTxNotSupported");
    }

    @Test
    void outcomeMapsToLegacyResult() {
        ValidatedTx validated = new ValidatedTx(new byte[] {1}, new byte[32], 10, 100, new byte[32], true,
                Origin.LOCAL);
        TxValidationOutcome valid = new TxValidationOutcome.Valid(
                TxEffects.ofChanges(Fixtures.hash32(1), List.of()), validated, false);
        TxValidationOutcome invalid = TxValidationOutcome.Invalid.of(LedgerFailure.eraNotSupported("byron"));

        assertThat(valid.isValid()).isTrue();
        assertThat(valid.toValidationResult().valid()).isTrue();
        assertThat(invalid.toValidationResult().valid()).isFalse();
        assertThat(invalid.toValidationResult().errors()).extracting(ValidationError::rule)
                .containsExactly("EraNotSupported");
        assertThatThrownBy(() -> new TxValidationOutcome.Invalid(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsCopyArraysDefensively() {
        byte[] cbor = {1, 2, 3};
        byte[] id = new byte[32];
        byte[] digest = {9};
        ValidatedTx validated = new ValidatedTx(cbor, id, 10, 100, digest, true, Origin.PEER);
        cbor[0] = 42;
        id[0] = 42;
        digest[0] = 42;
        validated.txCbor()[1] = 42;

        assertThat(validated.txCbor()).containsExactly(1, 2, 3);
        assertThat(validated.txId()[0]).isZero();
        assertThat(validated.validatedPhase2EnvDigest()).containsExactly(9);
        assertThat(validated).isEqualTo(new ValidatedTx(new byte[] {1, 2, 3}, new byte[32], 10, 100,
                new byte[] {9}, true, Origin.PEER));

        ValidationEnv env = Fixtures.env();
        env.phase2EnvDigest()[0] = 42;
        assertThat(env.phase2EnvDigest()[0]).isZero();
        assertThat(env).isEqualTo(Fixtures.env());

        byte[] tx = {7};
        TxValidationRequest request = new TxValidationRequest(tx, InMemoryLedgerView.builder().build(), env,
                Rule.MEMPOOL, Origin.LOCAL, null);
        tx[0] = 8;
        assertThat(request.txCbor()).containsExactly(7);
    }

    @Test
    void validatedTxRequiresThirtyTwoByteId() {
        assertThatThrownBy(() -> new ValidatedTx(new byte[1], new byte[31], 10, 1, new byte[0], true,
                Origin.SYNC)).isInstanceOf(IllegalArgumentException.class);
    }
}
