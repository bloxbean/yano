package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerHelper;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilder;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class DevnetProducerFactoryTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        BlockProducerHelper.resetEpochTrackingToSlot(-1);
    }

    @Test
    void createLiveInstallsDeferredDevnetProduction() {
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();

        var producer = factory(producerSubsystem).createLive(new DevnetBlockBuilder(), settings());

        assertThat(producer.isRunning()).isFalse();
        assertThat(producerSubsystem.modeOrNull()).isEqualTo(ProducerMode.DEVNET);
        assertThat(producerSubsystem.isRunning()).isFalse();

        producerSubsystem.start();

        assertThat(producer.isRunning()).isTrue();
        assertThat(producerSubsystem.isRunning()).isTrue();

        producerSubsystem.stop();
    }

    @Test
    void createTimeTravelInstallsDeferredDevnetTimeTravelProduction() {
        ProducerSubsystem producerSubsystem = new ProducerSubsystem();

        var producer = factory(producerSubsystem).createTimeTravel(new DevnetBlockBuilder(), settings());

        assertThat(producer.isRunning()).isFalse();
        assertThat(producerSubsystem.modeOrNull()).isEqualTo(ProducerMode.DEVNET_TIME_TRAVEL);
        assertThat(producerSubsystem.isRunning()).isFalse();
        assertThat(producerSubsystem.slotLengthMillis("test")).isEqualTo(1000);
    }

    private DevnetProducerFactory factory(ProducerSubsystem producerSubsystem) {
        return new DevnetProducerFactory(
                new DevnetProducerFactory.Dependencies(
                        new InMemoryChainState(),
                        emptyTransactions(),
                        () -> null,
                        new NoopEventBus(),
                        scheduler,
                        producerSubsystem));
    }

    private static DevnetProducerFactory.Settings settings() {
        return new DevnetProducerFactory.Settings(
                60_000,
                true,
                System.currentTimeMillis(),
                1000,
                null);
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
