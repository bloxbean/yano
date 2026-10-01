package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.RebuildBenchmark;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.scalusbridge.ScalusScriptPhaseEvaluator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 6a (review MAJOR-1): the Amaru adapter records the resolved-inputs digest; re-applying a reusable
 * {@code previous} runs the module in phase-one mode (its static checks cannot be skipped inside the module) and runs
 * no phase-2 evaluation.
 */
class AmaruEngineReapplyTest {

    private final CountingEvaluator evaluator = new CountingEvaluator(new ScalusScriptPhaseEvaluator());
    private final AmaruTransactionValidator engine = new AmaruTransactionValidator(
            LedgerValidationEngines.AMARU_SCALUS, AmaruEngineConfig.defaults(2),
            ScenarioSupport.network(MutationWorld.network()), evaluator, AmaruLedgerConstants.HASKELL,
            ScenarioSupport.wasm());
    private final InMemoryLedgerView world = MutationWorld.view();

    @AfterEach
    void close() {
        engine.close();
    }

    private TxValidationOutcome validate(byte[] tx, ValidatedTx previous) {
        return engine.validate(new TxValidationRequest(tx, world, MutationWorld.env(), TxValidationRequest.Rule.MEMPOOL,
                TxValidationRequest.Origin.LOCAL, previous));
    }

    @Test
    void aScriptTransactionIsReappliedWithoutPhaseTwo() {
        byte[] tx = ConwayTxBuilder.build(MutationWorld.scriptSpec(), world).cbor();

        TxValidationOutcome first = validate(tx, null);
        assertThat(first).as(String.valueOf(first)).isInstanceOf(TxValidationOutcome.Valid.class);
        TxValidationOutcome.Valid valid = (TxValidationOutcome.Valid) first;
        assertThat(valid.reapplied()).isFalse();
        assertThat(valid.validated().resolvedInputsDigest()).isNotNull();
        assertThat(evaluator.evaluations.get()).isEqualTo(1);

        TxValidationOutcome.Valid again = (TxValidationOutcome.Valid) validate(tx, valid.validated());
        assertThat(again.reapplied()).isTrue();
        assertThat(evaluator.evaluations.get()).as("no phase-2 run on re-application").isEqualTo(1);
    }

    @Test
    @Tag("benchmark")
    @EnabledIfSystemProperty(named = "yano.mempool.benchmark", matches = "true")
    void rebuildBenchmark() {
        RebuildBenchmark.Workload workload = RebuildBenchmark.workload(10_000);
        RebuildBenchmark.run(engine, RebuildBenchmark.workload(1_000)); // warm-up
        RebuildBenchmark.Result result = RebuildBenchmark.run(engine, workload);
        System.out.printf("amaru engine, %d txs: full %.0f ms, re-application %.0f ms (%d re-applied)%n",
                result.count(), result.fullMillis(), result.reapplyMillis(), result.reapplied());
        assertThat(result.reapplied()).isEqualTo(result.count());
    }

    /** Counts evaluations and delegates. */
    private static final class CountingEvaluator implements ScriptPhaseEvaluator {
        private final ScriptPhaseEvaluator delegate;
        final AtomicInteger evaluations = new AtomicInteger();

        CountingEvaluator(ScriptPhaseEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                          ProtocolParams params, SlotConfig slotConfig) {
            evaluations.incrementAndGet();
            return delegate.evaluate(txCbor, tx, resolvedInputs, params, slotConfig);
        }

        @Override
        public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                          ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
            evaluations.incrementAndGet();
            return delegate.evaluate(txCbor, tx, resolvedInputs, params, slotConfig, validationSlot);
        }

        @Override
        public List<LedgerFailure> collect(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                           ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
            return delegate.collect(txCbor, tx, resolvedInputs, params, slotConfig, validationSlot);
        }

        @Override
        public boolean isWellFormed(int language, byte[] script, int protocolMajor) {
            return delegate.isWellFormed(language, script, protocolMajor);
        }
    }
}
