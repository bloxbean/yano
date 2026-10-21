package org.yanoproject.ledger.rules.conway;

import com.bloxbean.cardano.client.api.model.ProtocolParams;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;

import java.math.BigInteger;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-application (ADR-056 §6): static checks and Plutus are skipped only when {@code previous} is still valid;
 * dynamic checks always run.
 */
class ReapplyTest {

    private final byte[] scriptTx = EngineTestSupport.build(MutationWorld.scriptSpec()).cbor();

    @Test
    void aFullValidationRecordsProvenanceThatAllowsReApplication() {
        StubEvaluator evaluator = new StubEvaluator();
        TxValidationOutcome.Valid first = valid(validate(evaluator, MutationWorld.view(), MutationWorld.env(),
                Origin.LOCAL, null));
        assertThat(first.reapplied()).isFalse();
        assertThat(first.validated().resolvedInputsDigest()).hasSize(32);
        assertThat(evaluator.evaluations).isOne();

        TxValidationOutcome.Valid again = valid(validate(evaluator, MutationWorld.view(), MutationWorld.env(),
                Origin.LOCAL, first.validated()));
        assertThat(again.reapplied()).isTrue();
        assertThat(again.validated()).isEqualTo(first.validated());
        assertThat(evaluator.evaluations).as("Plutus is static (when2Phase): not re-run").isOne();
        assertThat(evaluator.collections).as("CollectErrors is dynamic: prepared again").isEqualTo(2);
    }

    @Test
    void staticChecksAreSkippedAndDynamicChecksRunOnReApplication() {
        ValidatedTx previous = valid(validate(new StubEvaluator(), MutationWorld.view(), MutationWorld.env(),
                Origin.LOCAL, null)).validated();

        // maxTxSize is a static check (runTestOnSignal, Babbage/Rules/Utxo.hs:406)
        ProtocolParams smaller = MutationWorld.protocolParams();
        smaller.setMaxTxSize(100);
        InMemoryLedgerView view = MutationWorld.builder(smaller).build();
        assertThat(EngineTestSupport.names(validate(new StubEvaluator(), view, MutationWorld.env(), Origin.LOCAL,
                previous))).containsExactly("Valid");
        assertThat(EngineTestSupport.names(validate(new StubEvaluator(), view, MutationWorld.env(), Origin.LOCAL,
                null))).containsExactly("UTXO.MaxTxSizeUTxO");

        // the validity interval is dynamic (runTest, :359): the slot moved past the TTL
        ValidationEnv later = new ValidationEnv(MutationWorld.TTL, MutationWorld.env().currentEpoch(), 10, 0,
                MutationWorld.env().networkId(), MutationWorld.env().slotConfig(), MutationWorld.env().phase2EnvDigest());
        assertThat(EngineTestSupport.names(validate(new StubEvaluator(), MutationWorld.view(), later, Origin.LOCAL,
                previous))).containsExactly("UTXO.OutsideValidityIntervalUTxO");
    }

    @Test
    void invalidationRulesForceFullValidation() {
        ValidatedTx previous = valid(validate(new StubEvaluator(), MutationWorld.view(), MutationWorld.env(),
                Origin.LOCAL, null)).validated();
        byte[] txId = previous.txId();
        byte[] digest = previous.resolvedInputsDigest();
        ValidationEnv env = MutationWorld.env();

        assertThat(ReapplyPolicy.decide(previous, txId, true, 10, env, Origin.LOCAL, digest).reapply()).isTrue();
        assertThat(ReapplyPolicy.decide(previous, txId, true, 10, env, Origin.BLOCK_BUILD, digest).reapply())
                .as("an admission verdict is re-used for block building").isTrue();
        assertThat(ReapplyPolicy.decide(previous, txId, true, 11, env, Origin.LOCAL, digest).reason())
                .contains("protocol major version");
        ValidationEnv otherCostModels = new ValidationEnv(env.currentSlot(), env.currentEpoch(), 10, 0,
                env.networkId(), env.slotConfig(), new byte[]{1});
        assertThat(ReapplyPolicy.decide(previous, txId, true, 10, otherCostModels, Origin.LOCAL, digest).reason())
                .contains("phase-2 environment");
        byte[] otherDigest = digest.clone();
        otherDigest[0] ^= 1;
        assertThat(ReapplyPolicy.decide(previous, txId, true, 10, env, Origin.LOCAL, otherDigest).reason())
                .contains("different output");
        assertThat(ReapplyPolicy.decide(previous, txId, false, 10, env, Origin.LOCAL, digest).reapply()).isFalse();
        byte[] otherTx = Arrays.copyOf(txId, 32);
        otherTx[0] ^= 1;
        assertThat(ReapplyPolicy.decide(previous, otherTx, true, 10, env, Origin.LOCAL, digest).reapply()).isFalse();

        ValidatedTx fromSync = new ValidatedTx(previous.txCbor(), txId, 10, previous.validatedEpoch(),
                previous.validatedPhase2EnvDigest(), true, Origin.SYNC, digest);
        assertThat(ReapplyPolicy.decide(fromSync, txId, true, 10, env, Origin.PEER, digest).reason())
                .contains("SYNC verdict");
        assertThat(ReapplyPolicy.decide(fromSync, txId, true, 10, env, Origin.SYNC, digest).reapply()).isTrue();

        ValidatedTx otherEngine = new ValidatedTx(previous.txCbor(), txId, 10, previous.validatedEpoch(),
                previous.validatedPhase2EnvDigest(), true, Origin.LOCAL);
        assertThat(ReapplyPolicy.decide(otherEngine, txId, true, 10, env, Origin.LOCAL, digest).reason())
                .contains("not recorded");
    }

    @Test
    void aChangedInputForcesFullValidationIncludingPlutus() {
        ValidatedTx previous = valid(validate(new StubEvaluator(), MutationWorld.view(), MutationWorld.env(),
                Origin.LOCAL, null)).validated();
        InMemoryLedgerView.Builder builder = MutationWorld.builder(MutationWorld.protocolParams());
        builder.utxo(MutationWorld.SCRIPT_INPUT.getTransactionId(), 0,
                MutationWorld.output(MutationWorld.scriptAddress(), MutationWorld.SCRIPT_INPUT_LOVELACE.add(
                        BigInteger.ONE)));
        StubEvaluator evaluator = new StubEvaluator();
        TxValidationOutcome outcome = validate(evaluator, builder.build(), MutationWorld.env(), Origin.LOCAL, previous);
        assertThat(EngineTestSupport.names(outcome)).containsExactly("UTXO.ValueNotConservedUTxO");
        assertThat(evaluator.evaluations).isZero(); // a failure stops Plutus, but the decision was a full run
    }

    private TxValidationOutcome validate(StubEvaluator evaluator, LedgerView view, ValidationEnv env, Origin origin,
                                         ValidatedTx previous) {
        return new JavaLedgerValidationEngine(evaluator).validate(new TxValidationRequest(scriptTx, view, env,
                TxValidationRequest.Rule.LEDGER, origin, previous));
    }

    private static TxValidationOutcome.Valid valid(TxValidationOutcome outcome) {
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Valid.class);
        return (TxValidationOutcome.Valid) outcome;
    }
}
