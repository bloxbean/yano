package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void nextSparseCandidateSlot_stepsByIntervalButNeverPastAnEpochStart() {
        BlockProducerHelper.setEpochParamProvider(new EpochLengthProvider(250));
        try {
            assertThat(SlotLeaderTimeTravelBlockProducer.nextSparseCandidateSlot(1, 0, 100)).isEqualTo(100);
            assertThat(SlotLeaderTimeTravelBlockProducer.nextSparseCandidateSlot(201, 200, 100)).isEqualTo(250);
            assertThat(SlotLeaderTimeTravelBlockProducer.nextSparseCandidateSlot(251, 250, 100)).isEqualTo(350);
            // an interval longer than an epoch still visits every epoch start
            assertThat(SlotLeaderTimeTravelBlockProducer.nextSparseCandidateSlot(1, 0, 1_000)).isEqualTo(250);
            // never earlier than the slot being scanned, and interval 1 or no tip means no skipping
            assertThat(SlotLeaderTimeTravelBlockProducer.nextSparseCandidateSlot(400, 0, 100)).isEqualTo(400);
            assertThat(SlotLeaderTimeTravelBlockProducer.nextSparseCandidateSlot(7, 6, 1)).isEqualTo(7);
            assertThat(SlotLeaderTimeTravelBlockProducer.nextSparseCandidateSlot(7, -1, 100)).isEqualTo(7);
        } finally {
            BlockProducerHelper.setEpochParamProvider(null);
        }
    }

    @Test
    void catchUpSkipsSlotsThenScansUntilEligibleOrTarget() {
        InMemoryChainState chainState = new InMemoryChainState();
        var existing = new DevnetBlockBuilder().buildBlock(0, 0, null, List.of());
        chainState.storeBlock(existing.blockHash(), 0L, 0L, existing.blockCbor());
        chainState.storeBlockHeader(existing.blockHash(), 0L, 0L, existing.wrappedHeaderCbor());

        List<Long> checkedSlots = new ArrayList<>();
        SlotLeaderCheck countingNeverLeader = new SlotLeaderCheck(new byte[64], BigDecimal.ONE, null) {
            @Override
            public BlockSigner.VrfSignResult checkAndProve(long slot, byte[] epochNonce, BigDecimal sigma) {
                checkedSlots.add(slot);
                return null;
            }
        };
        StakeDataProvider fullStake = new StakeDataProvider() {
            @Override
            public BigInteger getPoolStake(String poolHash, int epoch) {
                return BigInteger.ONE;
            }

            @Override
            public BigInteger getTotalStake(int epoch) {
                return BigInteger.ONE;
            }
        };
        EpochNonceState nonceState = new EpochNonceState(10_000, 1_000, 1.0);
        nonceState.initFromGenesisHash(new byte[32]);
        var producer = SlotLeaderTimeTravelBlockProducer.withTransactionSelector(
                chainState, emptyTransactions(), () -> null, new NoopEventBus(), scheduler,
                null, nonceState, countingNeverLeader, fullStake,
                "pool", System.currentTimeMillis() - 60_000, 1000, 1, 1);
        producer.setBackfillBlockIntervalSlots(100);

        producer.produceToSlot(1_000);

        assertThat(checkedSlots).isNotEmpty();
        assertThat(checkedSlots.get(0)).isEqualTo(100L);
        assertThat(checkedSlots).hasSize(901);
        assertThat(producer.getLastCheckedSlot()).isEqualTo(1_000);

        // a catch-up that ends inside the interval forges nothing and lands on the target
        checkedSlots.clear();
        producer.setBackfillBlockIntervalSlots(5_000);
        producer.produceToSlot(1_500);
        assertThat(checkedSlots).isEmpty();
        assertThat(producer.getLastCheckedSlot()).isEqualTo(1_500);
    }

    @Test
    void setBackfillBlockIntervalSlots_rejectsValuesBelowOne() {
        var producer = SlotLeaderTimeTravelBlockProducer.withTransactionSelector(
                new InMemoryChainState(), emptyTransactions(), () -> null, new NoopEventBus(), scheduler,
                null, new EpochNonceState(10, 1, 1.0), null, null,
                "pool", System.currentTimeMillis() - 60_000, 1000, 1, 1);
        assertThatThrownBy(() -> producer.setBackfillBlockIntervalSlots(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(producer.getBackfillBlockIntervalSlots()).isEqualTo(1);
    }

    @Test
    void sparseCatchUpForgesSignedBlocksAcrossThreeEpochsThenResumesDenseScheduling() throws Exception {
        Path base = Path.of("src/test/resources/devnet");
        var keys = BlockProducerKeys.load(base.resolve("vrf.skey"), base.resolve("kes.skey"), base.resolve("opcert.cert"));
        var nonce = new EpochNonceState(1_200, 100, 1);
        nonce.initFromGenesisHash(new byte[32]);
        var builder = new SignedBlockBuilder(keys, 129_600, 60, nonce, null);
        var chain = new InMemoryChainState();
        var genesis = builder.buildBlock(0, 0, null, List.of());
        BlockProducerHelper.storeProducedBlock(chain, builder, genesis);
        List<Long> checked = new ArrayList<>();
        CountDownLatch liveCheck = new CountDownLatch(1);
        var check = new SlotLeaderCheck(keys.getVrfSkey(), BigDecimal.ONE, builder.getBlockSigner()) {
            @Override
            public BlockSigner.VrfSignResult checkAndProve(long slot, byte[] epochNonce, BigDecimal sigma) {
                checked.add(slot);
                if (slot > 3_600) liveCheck.countDown();
                return super.checkAndProve(slot, epochNonce, sigma);
            }
        };
        var producer = SlotLeaderTimeTravelBlockProducer.withTransactionSelector(chain, emptyTransactions(),
                () -> null, new NoopEventBus(), scheduler, builder, nonce, check, fullStake(), "pool",
                System.currentTimeMillis() - 3_610 * 300L, 300, 10, 1);
        producer.setBackfillBlockIntervalSlots(BackfillPolicy.resolveInterval(0, 100, 1, true));
        assertThat(producer.produceToSlot(3_600)).isEqualTo(24);
        assertThat(checked).hasSize(24).contains(1_200L, 2_400L, 3_600L);
        assertThat(chain.getTip().getSlot()).isEqualTo(3_600);
        assertThat(nonce.getCurrentEpoch()).isEqualTo(3);
        producer.start();
        try {
            assertThat(liveCheck.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            producer.stop();
        }
        assertThat(checked.get(24)).isEqualTo(3_601L);
    }

    @Test
    void sparseCatchUpStopsBeforeExceedingForecastWindow() {
        var chain = new InMemoryChainState();
        var genesis = new DevnetBlockBuilder().buildBlock(0, 0, null, List.of());
        chain.storeBlock(genesis.blockHash(), 0L, 0L, genesis.blockCbor());
        var nonce = new EpochNonceState(1_200, 100, 1);
        nonce.initFromGenesisHash(new byte[32]);
        List<Long> checked = new ArrayList<>();
        var check = new SlotLeaderCheck(new byte[64], BigDecimal.ONE, null) {
            @Override
            public BlockSigner.VrfSignResult checkAndProve(long slot, byte[] epochNonce, BigDecimal sigma) {
                checked.add(slot);
                return null;
            }
        };
        var producer = SlotLeaderTimeTravelBlockProducer.withTransactionSelector(chain, emptyTransactions(),
                () -> null, new NoopEventBus(), scheduler, null, nonce, check, fullStake(), "pool",
                0, 300, 300, 1);
        producer.setBackfillBlockIntervalSlots(150);
        assertThatThrownBy(() -> producer.produceToSlot(3_600))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("forecast window");
        assertThat(checked).hasSize(150).startsWith(150L).endsWith(299L);
        assertThat(producer.getLastCheckedSlot()).isEqualTo(299);
    }

    private static StakeDataProvider fullStake() {
        return new StakeDataProvider() {
            public BigInteger getPoolStake(String poolHash, int epoch) { return BigInteger.ONE; }
            public BigInteger getTotalStake(int epoch) { return BigInteger.ONE; }
        };
    }

    private static final class EpochLengthProvider implements EpochParamProvider {
        private final long epochLength;

        private EpochLengthProvider(long epochLength) {
            this.epochLength = epochLength;
        }

        @Override
        public BigInteger getKeyDeposit(long epoch) {
            return BigInteger.ZERO;
        }

        @Override
        public BigInteger getPoolDeposit(long epoch) {
            return BigInteger.ZERO;
        }

        @Override
        public long getEpochLength() {
            return epochLength;
        }

        @Override
        public long getByronSlotsPerEpoch() {
            return epochLength;
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
