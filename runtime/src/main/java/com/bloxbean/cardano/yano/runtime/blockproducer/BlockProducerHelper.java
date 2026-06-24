package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.BlockProducedEvent;
import com.bloxbean.cardano.yano.api.events.EpochTransitionEvent;
import com.bloxbean.cardano.yano.api.events.GenesisBlockEvent;
import com.bloxbean.cardano.yano.api.events.PostEpochTransitionEvent;
import com.bloxbean.cardano.yano.api.events.PreEpochTransitionEvent;
import com.bloxbean.cardano.yano.api.genesis.GenesisBootstrapData;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.runtime.chain.MemPool;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelector;
import com.bloxbean.cardano.yano.runtime.tx.BlockTransactionSelectors;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared utilities for block producer implementations.
 * Eliminates code duplication between {@link DevnetBlockProducer} and {@link SlotLeaderBlockProducer}.
 */
@Slf4j
public final class BlockProducerHelper {

    // Shared epoch tracking for block producer paths.
    // Single-threaded access (block production is sequential).
    private static volatile int previousEpoch = -1;
    private static volatile EpochParamProvider epochProvider;
    private static volatile Supplier<GenesisBootstrapData> genesisBootstrapDataSupplier = GenesisBootstrapData::empty;
    private static volatile Supplier<String> producerPoolHashSupplier = () -> null;

    private BlockProducerHelper() {}

    /**
     * Set the epoch param provider for epoch transition detection in block producer mode.
     */
    public static void setEpochParamProvider(EpochParamProvider provider) {
        epochProvider = provider;
    }

    public static void setGenesisBootstrapDataSupplier(Supplier<GenesisBootstrapData> supplier) {
        genesisBootstrapDataSupplier = supplier != null ? supplier : GenesisBootstrapData::empty;
    }

    public static void setProducerPoolHashSupplier(Supplier<String> supplier) {
        producerPoolHashSupplier = supplier != null ? supplier : () -> null;
    }

    public static void resetEpochTrackingToSlot(long tipSlot) {
        previousEpoch = tipSlot >= 0 ? epochForSlot(tipSlot) : -1;
        log.info("Block producer epoch tracking reset to epoch {} at slot {}", previousEpoch, tipSlot);
    }

    private static void storeBlock(ChainState chainState, DevnetBlockBuilder.BlockBuildResult result) {
        chainState.storeBlockHeader(result.blockHash(), result.blockNumber(), result.slot(), result.wrappedHeaderCbor());
        chainState.storeBlock(result.blockHash(), result.blockNumber(), result.slot(), result.blockCbor());
    }

    /**
     * Store a locally produced block and finalize any nonce state staged while building it.
     * <p>
     * {@link SignedBlockBuilder} mutates its in-memory nonce state during block assembly, but
     * defers durable nonce snapshot writes until after the ChainState header/body cursor has
     * advanced. Producer code must use this method so nonce state cannot move ahead of the
     * durable block cursor if header/body storage fails.
     * <p>
     * For unsigned devnet builders, this method is equivalent to storing the header and body.
     */
    public static void storeProducedBlock(ChainState chainState,
                                          DevnetBlockBuilder blockBuilder,
                                          DevnetBlockBuilder.BlockBuildResult result) {
        if (blockBuilder instanceof SignedBlockBuilder signedBlockBuilder) {
            try {
                storeBlock(chainState, result);
            } catch (RuntimeException | Error e) {
                signedBlockBuilder.rollbackPendingNonceState();
                throw e;
            }
            signedBlockBuilder.commitPendingNonceState();
        } else {
            storeBlock(chainState, result);
        }
    }

    public static void publishEvent(EventBus eventBus, DevnetBlockBuilder.BlockBuildResult result,
                              int txCount, String origin) {
        publishEvent(eventBus, result, txCount, origin, true);
    }

    public static void publishEvent(EventBus eventBus, DevnetBlockBuilder.BlockBuildResult result,
                              int txCount, String origin, boolean includeGenesisEvent) {
        if (eventBus == null) return;

        String hashHex = HexUtil.encodeHexString(result.blockHash());
        EventMetadata meta = EventMetadata.builder()
                .origin(origin)
                .slot(result.slot())
                .blockNo(result.blockNumber())
                .blockHash(hashHex)
                .build();
        PublishOptions opts = PublishOptions.builder().build();

        try {
            eventBus.publish(
                    new BlockProducedEvent(Era.Conway.getValue(), result.slot(), result.blockNumber(),
                            result.blockHash(), txCount),
                    meta, opts);

            if (includeGenesisEvent) {
                publishGenesisBlockEventIfNeeded(eventBus, result, hashHex, meta, opts);
            }

            Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
            eventBus.publish(
                    new BlockAppliedEvent(Era.Conway, result.slot(), result.blockNumber(),
                            hashHex, block),
                    meta, opts);
        } catch (Exception e) {
            log.debug("Failed to publish block events: {}", e.getMessage());
            if (result.blockNumber() == 0) {
                throw new RuntimeException("Failed to publish genesis block events", e);
            }
        }
    }

    public static void publishGenesisBlockEvent(EventBus eventBus, DevnetBlockBuilder.BlockBuildResult result,
                                                String origin) {
        if (eventBus == null) return;

        String hashHex = HexUtil.encodeHexString(result.blockHash());
        EventMetadata meta = EventMetadata.builder()
                .origin(origin)
                .slot(result.slot())
                .blockNo(result.blockNumber())
                .blockHash(hashHex)
                .build();
        publishGenesisBlockEventIfNeeded(eventBus, result, hashHex, meta, PublishOptions.builder().build());
    }

    private static void publishGenesisBlockEventIfNeeded(EventBus eventBus, DevnetBlockBuilder.BlockBuildResult result,
                                                         String hashHex, EventMetadata meta, PublishOptions opts) {
        if (result.blockNumber() != 0) return;
        GenesisBootstrapData bootstrapData = genesisBootstrapDataSupplier.get();
        if (epochProvider == null) {
            if (bootstrapData != null
                    && (bootstrapData.hasShelleyStaking() || bootstrapData.shelleyGenesisHashHex() != null)) {
                throw new IllegalStateException("Genesis block event requires epoch metadata for enriched bootstrap data");
            }
            return;
        }
        int epoch = epochProvider.getEpochSlotCalc().slotToEpoch(result.slot());
        eventBus.publish(new GenesisBlockEvent(Era.Conway, epoch, result.slot(), result.blockNumber(), hashHex,
                        bootstrapData, producerPoolHashSupplier.get()),
                meta, opts);
        previousEpoch = epoch;
    }

    public static void notifyServer(NodeServer nodeServer) {
        if (nodeServer == null) return;
        try {
            nodeServer.notifyNewDataAvailable();
        } catch (Exception e) {
            log.warn("Failed to notify server of new block: {}", e.getMessage());
        }
    }

    public static BlockTransactionSelector transactionSelector(MemPool memPool,
                                                               TransactionValidationService validatorService,
                                                               UtxoState utxoState) {
        Objects.requireNonNull(memPool, "memPool");
        return BlockTransactionSelectors.fromMemPool(memPool, () -> validatorService, () -> utxoState, log);
    }

    public static List<byte[]> drainMempool(MemPool memPool,
                                      TransactionValidationService validatorService,
                                      UtxoState utxoState) {
        return transactionSelector(memPool, validatorService, utxoState).drainForBlock();
    }

    public static void prepareEpochTransitionBeforeBlock(EventBus eventBus, long slot, long blockNumber,
                                                         String origin) {
        if (eventBus == null) return;
        if (epochProvider == null) return;
        int currentEpoch = epochForSlot(slot);
        if (currentEpoch < 0) return;

        if (previousEpoch >= 0 && currentEpoch > previousEpoch) {
            log.info("Epoch transition detected (block producer): {} -> {} at slot {}, block {}",
                    previousEpoch, currentEpoch, slot, blockNumber);
            EventMetadata meta = EventMetadata.builder()
                    .origin(origin)
                    .slot(slot)
                    .blockNo(blockNumber)
                    .build();
            PublishOptions opts = PublishOptions.builder().build();
            eventBus.publish(new PreEpochTransitionEvent(previousEpoch, currentEpoch, slot, blockNumber),
                    meta, opts);
            eventBus.publish(new EpochTransitionEvent(previousEpoch, currentEpoch, slot, blockNumber),
                    meta, opts);
            eventBus.publish(new PostEpochTransitionEvent(previousEpoch, currentEpoch, slot, blockNumber),
                    meta, opts);
        }
        previousEpoch = currentEpoch;
    }

    private static int epochForSlot(long slot) {
        if (epochProvider == null) return -1;
        return epochProvider.getEpochSlotCalc().slotToEpoch(slot);
    }
}
