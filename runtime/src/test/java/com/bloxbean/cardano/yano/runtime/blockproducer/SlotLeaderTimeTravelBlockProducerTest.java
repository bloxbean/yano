package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SlotLeaderTimeTravelBlockProducerTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService stopper = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        stopper.shutdownNow();
        BlockProducerHelper.resetEpochTrackingToSlot(-1);
    }

    @Test
    void stopWaitsForRunningScheduledProduction() throws Exception {
        CountDownLatch stakeReadStarted = new CountDownLatch(1);
        CountDownLatch releaseStakeRead = new CountDownLatch(1);
        StakeDataProvider blockingStakeData = new StakeDataProvider() {
            @Override
            public BigInteger getPoolStake(String poolHash, int epoch) {
                stakeReadStarted.countDown();
                try {
                    if (!releaseStakeRead.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("stake read was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return null;
            }

            @Override
            public BigInteger getTotalStake(int epoch) {
                return null;
            }
        };
        SlotLeaderCheck neverLeader = new SlotLeaderCheck(new byte[64], BigDecimal.ONE, null) {
            @Override
            public BlockSigner.VrfSignResult checkAndProve(long slot, byte[] epochNonce, BigDecimal sigma) {
                return null;
            }
        };
        var producer = SlotLeaderTimeTravelBlockProducer.withTransactionSelector(
                new InMemoryChainState(),
                emptyTransactions(),
                () -> null,
                new NoopEventBus(),
                scheduler,
                null,
                new EpochNonceState(10, 1, 1.0),
                neverLeader,
                blockingStakeData,
                "pool",
                System.currentTimeMillis() - 60_000,
                1000,
                1,
                1);

        try {
            producer.start();
            assertThat(stakeReadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> stop = stopper.submit(producer::stop);
            Thread.sleep(100);

            assertThat(stop.isDone()).isFalse();

            releaseStakeRead.countDown();
            stop.get(5, TimeUnit.SECONDS);
            assertThat(producer.isRunning()).isFalse();
        } finally {
            releaseStakeRead.countDown();
            producer.stop();
        }
    }

    private static BlockTransactionSelector emptyTransactions() {
        return new BlockTransactionSelector() {
            @Override
            public boolean hasPendingTransactions() {
                return false;
            }

            @Override
            public List<byte[]> drainForBlock() {
                return List.of();
            }
        };
    }
}
