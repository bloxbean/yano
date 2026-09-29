package org.yanoproject.runtime.mempool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.runtime.chain.MempoolAdmissionResult;
import org.yanoproject.runtime.tx.BlockTransactionSelector;
import org.yanoproject.runtime.tx.BlockTransactionSelectors;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.runtime.mempool.MempoolTestWorld.ADA;

/**
 * Review MAJOR-2: without a selection validator (for example the legacy Scalus validator failed to initialise while
 * an engine is configured), block selection never takes transactions from a ledger-state mempool that lags the
 * canonical tip, since it can still hold just-confirmed transactions.
 */
class LedgerMempoolSelectionTest {

    private final LedgerView world = MempoolTestWorld.world(MutationWorld.protocolParams(), 4);
    private final MempoolTestWorld.Chain chain = new MempoolTestWorld.Chain(world);
    private final MempoolTestWorld.StubEvaluator evaluator = new MempoolTestWorld.StubEvaluator();
    private final LedgerMempool mempool = MempoolTestWorld.mempool(chain, evaluator,
            new MempoolTestWorld.HeldExecutor(), LedgerMempool.Settings.defaults());

    @AfterEach
    void close() {
        mempool.close();
    }

    @Test
    void aLaggingLedgerMempoolIsNotSelectedWithoutAValidator() {
        mempool.start();
        byte[] tx = MempoolTestWorld.build(MempoolTestWorld.payment(MempoolTestWorld.extraInput(0),
                ADA.multiply(BigInteger.TWO)), world);
        assertThat(MempoolTestWorld.admit(mempool, tx).status()).isEqualTo(MempoolAdmissionResult.Status.ACCEPTED);
        BlockTransactionSelector selector = BlockTransactionSelectors.fromMemPool(() -> mempool, () -> null,
                () -> null, LoggerFactory.getLogger(getClass()));

        assertThat(selector.drainForBlock()).as("fresh: validated in order at the tip").hasSize(1);
        selector.blockSelectionCompleted();

        chain.publish(MempoolTestWorld.confirm(world, evaluator, tx));   // confirmed; no rebuild yet
        assertThat(selector.drainForBlock()).as("lagging: never re-include a confirmed transaction").isEmpty();

        assertThat(mempool.rebuildNow()).isTrue();
        assertThat(selector.drainForBlock()).isEmpty();
        assertThat(mempool.isEmpty()).isTrue();
    }
}
