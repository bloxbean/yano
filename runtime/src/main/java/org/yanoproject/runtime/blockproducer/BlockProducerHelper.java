package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.api.events.BlockProducedEvent;
import org.yanoproject.api.events.EpochTransitionEvent;
import org.yanoproject.api.events.GenesisBlockEvent;
import org.yanoproject.api.events.PostEpochTransitionEvent;
import org.yanoproject.api.events.PreEpochTransitionEvent;
import org.yanoproject.api.genesis.GenesisBootstrapData;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.runtime.chain.MemPool;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.tx.BlockTransactionSelector;
import org.yanoproject.runtime.tx.BlockTransactionSelectors;
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

    // When true, a multi-epoch jump (restart/restore at wall-clock slots) fires one
    // transition per skipped epoch instead of a single collapsed transition, so every
    // epoch gets full boundary processing (params, snapshot, AdaPot, rewards, governance).
    // Off by default: a long-idle devnet can jump thousands of epochs.
    private static volatile boolean processSkippedEpochs;

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

    /**
     * Enable per-epoch boundary processing across multi-epoch jumps
     * ({@code yano.block-producer.process-skipped-epochs}).
     */
    public static void setProcessSkippedEpochs(boolean enabled) {
        processSkippedEpochs = enabled;
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

    /**
     * Producer boundary section (ADR-056): runs {@link #prepareEpochTransitionBeforeBlock} as one
     * canonical write section, separate from the later store-and-apply section so block selection
     * can happen between them. A section with no transition does not publish a new generation.
     */
    public static void prepareEpochTransitionInWriteSection(ChainState chainState, EventBus eventBus, long slot,
                                                            long blockNumber, String origin) {
        try (CanonicalStateGate.WriteSection section = enterCanonicalWrite(chainState)) {
            if (!prepareEpochTransitionBeforeBlock(eventBus, slot, blockNumber, origin)) {
                section.markUnchanged();
            }
        }
    }

    /**
     * Inside the store section, just before a forged block with selected transactions is stored: verifies that the
     * selection is still valid for the canonical state (ADR-056 §6: a selection whose canonical generation changed
     * is discarded, never forged). On failure the section is marked unchanged and a signed builder's pending nonce
     * state is rolled back.
     *
     * @throws StaleBlockSelectionException when the selection is stale
     */
    public static void requireCurrentSelection(BlockTransactionSelector transactions,
                                               CanonicalStateGate.WriteSection section,
                                               DevnetBlockBuilder blockBuilder, long slot) {
        if (transactions.selectionCurrent()) {
            return;
        }
        throw discardBuiltBlock(section, blockBuilder, slot);
    }

    /**
     * Discards a built block that must not be stored because the canonical state moved since it was built: the
     * section stays unchanged and the nonce state staged by the build is restored.
     *
     * @return the exception for the caller to throw
     */
    public static StaleBlockSelectionException discardBuiltBlock(CanonicalStateGate.WriteSection section,
                                                                 DevnetBlockBuilder blockBuilder, long slot) {
        section.markUnchanged();
        if (blockBuilder instanceof SignedBlockBuilder signedBlockBuilder) {
            signedBlockBuilder.rollbackPendingNonceState();
        }
        return new StaleBlockSelectionException(slot);
    }

    /**
     * Enters the canonical write section for storing a produced block and applying it (ADR-056).
     * Close it after the block's {@code BlockAppliedEvent} has been published; mempool notifications
     * raised while it is open run when it closes.
     */
    public static CanonicalStateGate.WriteSection enterCanonicalWrite(ChainState chainState) {
        return CanonicalStateGate.of(chainState).enterWrite();
    }

    /**
     * @return true when an epoch transition was published
     */
    public static boolean prepareEpochTransitionBeforeBlock(EventBus eventBus, long slot, long blockNumber,
                                                            String origin) {
        if (eventBus == null) return false;
        if (epochProvider == null) return false;
        int currentEpoch = epochForSlot(slot);
        if (currentEpoch < 0) return false;

        boolean transitioned = false;
        if (previousEpoch >= 0 && currentEpoch > previousEpoch) {
            transitioned = true;
            log.info("Epoch transition detected (block producer): {} -> {} at slot {}, block {}",
                    previousEpoch, currentEpoch, slot, blockNumber);
            EventMetadata meta = EventMetadata.builder()
                    .origin(origin)
                    .slot(slot)
                    .blockNo(blockNumber)
                    .build();
            PublishOptions opts = PublishOptions.builder().build();

            if (processSkippedEpochs && currentEpoch - previousEpoch > 1) {
                // Fire one full transition per skipped epoch so each gets complete
                // boundary processing (params, snapshot, AdaPot, rewards, governance).
                log.info("Processing {} skipped epoch transitions individually ({} -> {})",
                        currentEpoch - previousEpoch, previousEpoch, currentEpoch);
                for (int epoch = previousEpoch + 1; epoch <= currentEpoch; epoch++) {
                    publishEpochTransition(eventBus, epoch - 1, epoch, slot, blockNumber, meta, opts);
                }
            } else {
                publishEpochTransition(eventBus, previousEpoch, currentEpoch, slot, blockNumber, meta, opts);
            }
        }
        previousEpoch = currentEpoch;
        return transitioned;
    }

    private static void publishEpochTransition(EventBus eventBus, int fromEpoch, int toEpoch,
                                               long slot, long blockNumber,
                                               EventMetadata meta, PublishOptions opts) {
        eventBus.publish(new PreEpochTransitionEvent(fromEpoch, toEpoch, slot, blockNumber),
                meta, opts);
        eventBus.publish(new EpochTransitionEvent(fromEpoch, toEpoch, slot, blockNumber),
                meta, opts);
        eventBus.publish(new PostEpochTransitionEvent(fromEpoch, toEpoch, slot, blockNumber),
                meta, opts);
    }

    private static int epochForSlot(long slot) {
        if (epochProvider == null) return -1;
        return epochProvider.getEpochSlotCalc().slotToEpoch(slot);
    }

    /**
     * First slot of the epoch after the one containing {@code slot}, or -1 when no epoch
     * provider is installed. Used by sparse backfills so every epoch starts with a block.
     */
    public static long firstSlotOfNextEpoch(long slot) {
        if (epochProvider == null || slot < 0) return -1;
        var calc = epochProvider.getEpochSlotCalc();
        return calc.epochToStartSlot(calc.slotToEpoch(slot) + 1);
    }
}
