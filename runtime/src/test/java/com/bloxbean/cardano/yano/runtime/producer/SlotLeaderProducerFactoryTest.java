package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerHelper;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolVersionSupplier;
import com.bloxbean.cardano.yano.runtime.blockproducer.StakeDataProvider;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class SlotLeaderProducerFactoryTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        BlockProducerHelper.resetEpochTrackingToSlot(-1);
    }

    @Test
    void startLiveInstallsAndStartsSlotLeaderProduction() throws Exception {
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        EpochNonceState nonceState = nonceState();
        SlotLeaderSigningComponents signingComponents = signingComponents(nonceState);

        var producer = factory(producerSubsystem).startLive(
                signingComponents.signedBlockBuilder(),
                nonceState,
                signingComponents.slotLeaderCheck(),
                fixedStake(),
                "pool",
                System.currentTimeMillis(),
                60_000);

        assertThat(producer.isRunning()).isTrue();
        assertThat(producerSubsystem.modeOrNull()).isEqualTo(ProducerMode.SLOT_LEADER);
        assertThat(producerSubsystem.isRunning()).isTrue();

        producerSubsystem.stop();
    }

    @Test
    void createTimeTravelInstallsDeferredSlotLeaderTimeTravelProduction() throws Exception {
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();
        EpochNonceState nonceState = nonceState();
        SlotLeaderSigningComponents signingComponents = signingComponents(nonceState);

        var producer = factory(producerSubsystem).createTimeTravel(
                signingComponents.signedBlockBuilder(),
                nonceState,
                signingComponents.slotLeaderCheck(),
                fixedStake(),
                "pool",
                System.currentTimeMillis(),
                1000,
                100,
                1000);

        assertThat(producer.isRunning()).isFalse();
        assertThat(producerSubsystem.modeOrNull()).isEqualTo(ProducerMode.SLOT_LEADER_TIME_TRAVEL);
        assertThat(producerSubsystem.isRunning()).isFalse();
    }

    private SlotLeaderProducerFactory factory(ProducerSubsystem producerSubsystem) {
        return new SlotLeaderProducerFactory(
                new SlotLeaderProducerFactory.Dependencies(
                        new InMemoryChainState(),
                        emptyTransactions(),
                        () -> null,
                        new NoopEventBus(),
                        scheduler,
                        producerSubsystem));
    }

    private static SlotLeaderSigningComponents signingComponents(EpochNonceState nonceState) throws Exception {
        SlotLeaderKeyMaterial keyMaterial = SlotLeaderKeyMaterial.load(
                DEVNET_FIXTURE.resolve("vrf.skey"),
                DEVNET_FIXTURE.resolve("kes.skey"),
                DEVNET_FIXTURE.resolve("opcert.cert"));
        return SlotLeaderSigningComponents.create(
                keyMaterial,
                129600,
                60,
                nonceState,
                null,
                ProtocolVersionSupplier.fixed(11, 0),
                1.0);
    }

    private static EpochNonceState nonceState() {
        return new EpochNonceState(
                1200,
                100,
                1.0,
                Constants.BYRON_SLOTS_PER_EPOCH);
    }

    private static StakeDataProvider fixedStake() {
        return new StakeDataProvider() {
            @Override
            public BigInteger getPoolStake(String poolHash, int epoch) {
                return BigInteger.ONE;
            }

            @Override
            public BigInteger getTotalStake(int epoch) {
                return BigInteger.ONE;
            }
        };
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
