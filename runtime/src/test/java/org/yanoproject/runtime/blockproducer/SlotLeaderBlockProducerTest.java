package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.runtime.chain.InMemoryChainState;
import org.yanoproject.runtime.producer.SlotLeaderKeyMaterial;
import org.yanoproject.runtime.producer.SlotLeaderSigningComponents;
import org.yanoproject.runtime.tx.BlockTransactionSelector;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live slot-leader producer's safety rules, driven slot by slot ({@code checkSlot}) on an in-memory chain with
 * the devnet keys and {@code f = 1} (elected in every slot it checks).
 */
class SlotLeaderBlockProducerTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");
    // The system start lies a day ahead, so the producer's own slot ticks never fire during a test.
    private static final SlotClock FUTURE_CLOCK =
            SlotClock.shelleyOnly(System.currentTimeMillis() + 86_400_000L, 1_000);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final InMemoryChainState chainState = new InMemoryChainState();
    private final AtomicBoolean caughtUp = new AtomicBoolean(true);
    private final AtomicReference<BigInteger> poolStake = new AtomicReference<>(BigInteger.ONE);
    private EpochNonceState nonceState;
    private SlotLeaderSigningComponents signing;

    @BeforeEach
    void setUp() throws Exception {
        nonceState = new EpochNonceState(1200, 100, 1.0, Constants.BYRON_SLOTS_PER_EPOCH);
        nonceState.initFromGenesis("slot-leader-producer-test".getBytes());
        signing = SlotLeaderSigningComponents.create(
                SlotLeaderKeyMaterial.load(DEVNET_FIXTURE.resolve("vrf.skey"), DEVNET_FIXTURE.resolve("kes.skey"),
                        DEVNET_FIXTURE.resolve("opcert.cert")),
                129600, 60, nonceState, chainState, ProtocolVersionSupplier.fixed(10, 0), 1.0);
        var genesis = signing.signedBlockBuilder().buildBlock(0, 0, null, List.of());
        BlockProducerHelper.storeProducedBlock(chainState, signing.signedBlockBuilder(), genesis);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        BlockProducerHelper.resetEpochTrackingToSlot(-1);
    }

    @Test
    void aRestartAfterARollbackNeverForgesAnAlreadyForgedSlotAgain() {
        SlotLeaderBlockProducer first = startedProducer();
        first.checkSlot(5);
        assertThat(chainState.getTip().getSlot()).isEqualTo(5);
        assertThat(chainState.getLastForgedSlot()).as("persisted before the block was stored").isEqualTo(5);
        first.stop();

        // The forged block is rolled back (lost a slot battle), then the node restarts within the same slot.
        chainState.rollbackTo(0L);
        SlotLeaderBlockProducer restarted = startedProducer();
        restarted.checkSlot(5);
        assertThat(chainState.getTip().getSlot()).as("seeded from the last forged slot").isZero();

        // Even when the check cursor is reset to the tip (a rollback while running), slot 5 stays forged.
        restarted.resetToChainTip();
        restarted.checkSlot(5);
        assertThat(chainState.getTip().getSlot()).isZero();

        restarted.checkSlot(6);
        assertThat(chainState.getTip().getSlot()).isEqualTo(6);
        assertThat(chainState.getLastForgedSlot()).isEqualTo(6);
    }

    @Test
    void neverForgesASlotAtOrBeforeTheTip() {
        SlotLeaderBlockProducer producer = startedProducer();
        // A block for slot 10 arrives from upstream (stored directly here) while the wall clock is at slot 8.
        var upstream = signing.signedBlockBuilder().buildBlock(1, 10, chainState.getTip().getBlockHash(), List.of());
        BlockProducerHelper.storeProducedBlock(chainState, signing.signedBlockBuilder(), upstream);

        producer.checkSlot(8);
        producer.checkSlot(10);
        assertThat(chainState.getTip().getSlot()).isEqualTo(10);
        assertThat(chainState.getLastForgedSlot()).isEqualTo(-1);

        producer.checkSlot(11);
        assertThat(chainState.getTip().getSlot()).isEqualTo(11);
    }

    @Test
    void doesNotCheckLeadershipUntilCaughtUp() {
        SlotLeaderBlockProducer producer = startedProducer();
        caughtUp.set(false);

        producer.checkSlot(3);
        assertThat(chainState.getTip().getSlot()).isZero();

        caughtUp.set(true);
        producer.checkSlot(4);
        assertThat(chainState.getTip().getSlot()).isEqualTo(4);
    }

    @Test
    void aRollbackToAForgedBlockRestoresTheNonceStateAfterIt() {
        SlotLeaderBlockProducer producer = startedProducer();
        producer.checkSlot(5);
        byte[] afterSlot5 = nonceState.serialize();
        producer.checkSlot(6);
        assertThat(nonceState.serialize()).isNotEqualTo(afterSlot5);

        assertThat(nonceState.rollbackTo(5)).as("the forged block left a rollback checkpoint").isTrue();
        assertThat(nonceState.serialize()).isEqualTo(afterSlot5);
    }

    @Test
    void aMissingStakeDistributionIsZeroStakeNotTheLastEpochsStake() {
        SlotLeaderBlockProducer producer = startedProducer();
        producer.checkSlot(1);
        assertThat(chainState.getTip().getSlot()).isEqualTo(1);

        // Epoch 1 (slots 1200..2399) has no stake data: no block, and no stale stake from epoch 0.
        poolStake.set(null);
        producer.checkSlot(1_200);
        assertThat(chainState.getTip().getSlot()).isEqualTo(1);

        // The source is asked again only after the retry interval.
        poolStake.set(BigInteger.ONE);
        producer.checkSlot(1_201);
        assertThat(chainState.getTip().getSlot()).isEqualTo(1);
        producer.checkSlot(1_200 + SlotLeaderBlockProducer.STAKE_RETRY_SLOTS);
        assertThat(chainState.getTip().getSlot()).isEqualTo(1_200 + SlotLeaderBlockProducer.STAKE_RETRY_SLOTS);
    }

    private SlotLeaderBlockProducer startedProducer() {
        SlotLeaderBlockProducer producer = new SlotLeaderBlockProducer(chainState, noTransactions(), () -> null,
                new NoopEventBus(), scheduler, signing.signedBlockBuilder(), nonceState, signing.slotLeaderCheck(),
                stake(), "pool", FUTURE_CLOCK, tip -> caughtUp.get());
        producer.start();
        return producer;
    }

    private StakeDataProvider stake() {
        return new StakeDataProvider() {
            @Override
            public BigInteger getPoolStake(String poolHash, int epoch) {
                return poolStake.get();
            }

            @Override
            public BigInteger getTotalStake(int epoch) {
                return BigInteger.ONE;
            }
        };
    }

    private static BlockTransactionSelector noTransactions() {
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
