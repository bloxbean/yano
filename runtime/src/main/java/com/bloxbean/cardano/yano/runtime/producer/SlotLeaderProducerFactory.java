package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yano.runtime.blockproducer.EpochNonceState;
import com.bloxbean.cardano.yano.runtime.blockproducer.SignedBlockBuilder;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderBlockProducer;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderCheck;
import com.bloxbean.cardano.yano.runtime.blockproducer.SlotLeaderTimeTravelBlockProducer;
import com.bloxbean.cardano.yano.runtime.blockproducer.StakeDataProvider;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Assembles slot-leader producer strategies and installs them in the producer subsystem.
 */
@Slf4j
public final class SlotLeaderProducerFactory {
    private final Dependencies dependencies;

    public SlotLeaderProducerFactory(Dependencies dependencies) {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    public SlotLeaderBlockProducer startLive(SignedBlockBuilder signedBlockBuilder,
                                             EpochNonceState epochNonceState,
                                             SlotLeaderCheck slotLeaderCheck,
                                             StakeDataProvider stakeDataProvider,
                                             String poolHash,
                                             long resolvedGenesisTimestamp,
                                             int slotLengthMillis) {
        var producer = SlotLeaderBlockProducer.withTransactionSelector(
                dependencies.chainState(),
                dependencies.transactions(),
                dependencies.nodeServerSupplier(),
                dependencies.eventBus(),
                dependencies.scheduler(),
                signedBlockBuilder,
                epochNonceState,
                slotLeaderCheck,
                stakeDataProvider,
                poolHash,
                resolvedGenesisTimestamp,
                slotLengthMillis);
        dependencies.producerSubsystem().installSlotLeader(producer);
        dependencies.producerSubsystem().start();
        log.info("Block producer started (slot-leader mode, pool={}, slotLength={}ms)", poolHash, slotLengthMillis);
        return producer;
    }

    public SlotLeaderTimeTravelBlockProducer createTimeTravel(SignedBlockBuilder signedBlockBuilder,
                                                              EpochNonceState epochNonceState,
                                                              SlotLeaderCheck slotLeaderCheck,
                                                              StakeDataProvider stakeDataProvider,
                                                              String poolHash,
                                                              long resolvedGenesisTimestamp,
                                                              int slotLengthMillis,
                                                              int blockTimeMillis,
                                                              long sequentialScanLimitSlots) {
        var producer = SlotLeaderTimeTravelBlockProducer.withTransactionSelector(
                dependencies.chainState(),
                dependencies.transactions(),
                dependencies.nodeServerSupplier(),
                dependencies.eventBus(),
                dependencies.scheduler(),
                signedBlockBuilder,
                epochNonceState,
                slotLeaderCheck,
                stakeDataProvider,
                poolHash,
                resolvedGenesisTimestamp,
                slotLengthMillis,
                blockTimeMillis,
                sequentialScanLimitSlots);
        dependencies.producerSubsystem().installSlotLeaderTimeTravel(producer);
        return producer;
    }

    /**
     * Runtime collaborators required to install slot-leader producer
     * strategies.
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
