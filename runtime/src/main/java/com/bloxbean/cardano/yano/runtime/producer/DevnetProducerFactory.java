package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilder;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockProducer;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisConfig;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Assembles devnet producer strategies and installs them in the producer subsystem.
 */
public final class DevnetProducerFactory {
    private final Dependencies dependencies;

    public DevnetProducerFactory(Dependencies dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    public DevnetBlockProducer createLive(DevnetBlockBuilder blockBuilder, Settings settings) {
        return create(blockBuilder, settings, false);
    }

    public DevnetBlockProducer createTimeTravel(DevnetBlockBuilder blockBuilder, Settings settings) {
        return create(blockBuilder, settings, true);
    }

    private DevnetBlockProducer create(DevnetBlockBuilder blockBuilder, Settings settings, boolean timeTravel) {
        Objects.requireNonNull(blockBuilder, "blockBuilder");
        Objects.requireNonNull(settings, "settings");

        var producer = DevnetBlockProducer.withTransactionSelector(
                dependencies.chainState(),
                dependencies.transactions(),
                dependencies.nodeServerSupplier(),
                dependencies.eventBus(),
                dependencies.scheduler(),
                blockBuilder,
                settings.blockTimeMillis(),
                settings.lazyBlockProduction(),
                settings.resolvedGenesisTimestamp(),
                settings.slotLengthMillis(),
                settings.genesisConfig());
        dependencies.producerSubsystem().installDevnet(producer, timeTravel);
        return producer;
    }

    /**
     * Runtime settings used when creating a devnet producer instance.
     */
    public record Settings(
            int blockTimeMillis,
            boolean lazyBlockProduction,
            long resolvedGenesisTimestamp,
            int slotLengthMillis,
            GenesisConfig genesisConfig) {
    }

    /**
     * Runtime collaborators required to install devnet producer strategies.
     */
    public record Dependencies(
            ChainState chainState,
            BlockTransactionSelector transactions,
            Supplier<NodeServer> nodeServerSupplier,
            EventBus eventBus,
            ScheduledExecutorService scheduler,
            ProducerSubsystem producerSubsystem) {
        public Dependencies {
            Objects.requireNonNull(chainState, "chainState");
            Objects.requireNonNull(transactions, "transactions");
            Objects.requireNonNull(nodeServerSupplier, "nodeServerSupplier");
            Objects.requireNonNull(eventBus, "eventBus");
            Objects.requireNonNull(scheduler, "scheduler");
            Objects.requireNonNull(producerSubsystem, "producerSubsystem");
        }
    }
}
