package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Evolves {@link EpochNonceState} from synced blocks received via the event bus.
 * Handles rollbacks by restoring from checkpoint ring buffer.
 * <p>
 * At epoch boundaries for TPraos eras (Shelley–Alonzo), the listener looks up
 * {@code extraEntropy} from the supplied {@link EpochParamProvider} and threads it
 * into the TICKN transition. For Babbage+ epochs the provider is not queried at all
 * (entropy never applies in Praos).
 * <p>
 * Registered via {@code AnnotationListenerRegistrar.register(eventBus, listener, defaults)}
 * in {@code Yano.startSlotLeaderBlockProducer()}.
 */
@Slf4j
public class NonceEvolutionListener {

    private final EpochNonceState nonceState;
    private final NonceStateStore nonceStore;  // nullable
    private final String ownIssuerVkey;        // hex, to skip own produced blocks (nullable)
    private final EpochNonceEvolver evolver;
    private final NonceCursorResolver cursorResolver; // nullable
    private final NonceReplayService replayService;   // nullable

    @FunctionalInterface
    public interface NonceCursorResolver {
        NonceStateSnapshot resolve(long slot, String hashHex, byte[] serializedNonceState);
    }

    /**
     * Backward-compatible constructor used by tests / paths that do not need TPraos extraEntropy.
     */
    public NonceEvolutionListener(EpochNonceState nonceState, NonceStateStore nonceStore,
                                   String ownIssuerVkey) {
        this(nonceState, nonceStore, ownIssuerVkey, null, false, 0L, null, null);
    }

    /**
     * @param nonceState                     shared nonce state to evolve
     * @param nonceStore                     optional persistence (null if not using RocksDB)
     * @param ownIssuerVkey                  hex-encoded issuer vkey of this node's pool (null if not producing)
     * @param paramProvider                  effective epoch-param provider for extraEntropy lookup (nullable)
     * @param trackedEpochParamsAvailable    true iff {@code paramProvider} is an enabled {@code EpochParamTracker};
     *                                       drives custom-network heuristic WARN
     * @param protocolMagic                  network magic, used for the mainnet epoch 259 hard guard
     */
    public NonceEvolutionListener(EpochNonceState nonceState, NonceStateStore nonceStore,
                                   String ownIssuerVkey, EpochParamProvider paramProvider,
                                   boolean trackedEpochParamsAvailable, long protocolMagic) {
        this(nonceState, nonceStore, ownIssuerVkey, paramProvider, trackedEpochParamsAvailable,
                protocolMagic, null, null);
    }

    public NonceEvolutionListener(EpochNonceState nonceState, NonceStateStore nonceStore,
                                   String ownIssuerVkey, EpochParamProvider paramProvider,
                                   boolean trackedEpochParamsAvailable, long protocolMagic,
                                   NonceCursorResolver cursorResolver,
                                   NonceReplayService replayService) {
        this.nonceState = nonceState;
        this.nonceStore = nonceStore;
        this.ownIssuerVkey = ownIssuerVkey;
        this.evolver = new EpochNonceEvolver(paramProvider, trackedEpochParamsAvailable, protocolMagic);
        this.cursorResolver = cursorResolver;
        this.replayService = replayService;
    }

    @DomainEventListener(order = 50)
    public void onBlockApplied(BlockAppliedEvent event) {
        Block block = event.block();
        if (block == null || block.getHeader() == null) return;

        Era era = event.era();
        HeaderBody hb = block.getHeader().getHeaderBody();
        if (hb == null) return;

        // Skip blocks we produced ourselves (SignedBlockBuilder already evolved nonce)
        if (ownIssuerVkey != null && ownIssuerVkey.equals(hb.getIssuerVkey())) {
            return;
        }

        long slot = event.slot();
        var result = evolver.evolve(nonceState, era, slot, hb);
        if (!result.applied()) return;

        // Log epoch transition with the new epoch nonce
        if (result.epochTransition()) {
            byte[] epochNonce = nonceState.getEpochNonce();
            if (nonceStore != null) {
                nonceStore.storeEpochNonce(result.epochAfter(), epochNonce);
            }
            log.info("Epoch nonce for epoch {}: {}",
                    result.epochAfter(), epochNonce != null ? HexUtil.encodeHexString(epochNonce) : null);
        }

        // Serialize once, reuse for both checkpoint and persistence
        byte[] serialized = nonceState.serialize();
        nonceState.saveCheckpoint(slot, serialized);

        if (nonceStore != null) {
            NonceStateSnapshot snapshot = NonceStateSnapshot.of(
                    slot, event.blockNumber(), event.blockHash(), serialized);
            nonceStore.storeLatestNonceSnapshot(snapshot);
            if (result.epochTransition()) {
                nonceStore.storeEpochNonceCheckpoint(result.epochAfter(), snapshot);
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Nonce evolved at slot {} (epoch {}), evolvingNonce={}",
                    slot, nonceState.getCurrentEpoch(),
                    HexUtil.encodeHexString(nonceState.getEvolvingNonce()));
        }
    }

    /**
     * Defensive decoder around {@link HexUtil#decodeHexString(String)}. Trims, strips
     * optional {@code 0x}/{@code \x} prefixes, validates 32-byte length, catches decode
     * failures, and logs WARN. Never throws — returns {@code null} on any decode failure
     * so live sync is not blocked.
     */
    static byte[] decodeExtraEntropy(String raw, int epoch) {
        return EpochNonceEvolver.decodeExtraEntropy(raw, epoch);
    }

    @DomainEventListener(order = 50)
    public void onRollback(RollbackEvent event) {
        long targetSlot = event.target().getSlot();
        boolean restored = nonceState.rollbackTo(targetSlot);
        if (restored) {
            log.info("Nonce state rolled back to slot {}", targetSlot);
            if (nonceStore != null) {
                byte[] serialized = nonceState.serialize();
                NonceStateSnapshot snapshot;
                if (cursorResolver != null) {
                    snapshot = cursorResolver.resolve(targetSlot, event.target().getHash(), serialized);
                } else if (targetSlot < 0) {
                    snapshot = NonceStateSnapshot.origin(serialized);
                } else {
                    throw new IllegalStateException("Cannot persist rollback nonce snapshot without a body-tip cursor");
                }
                nonceStore.storeLatestNonceSnapshot(snapshot);
                nonceStore.pruneEpochNoncesAfter(nonceState.getCurrentEpoch());
                nonceStore.pruneEpochNonceCheckpointsAfter(nonceState.getCurrentEpoch());
            }
        } else {
            if (replayService == null) {
                throw new IllegalStateException("No nonce checkpoint found for rollback to slot "
                        + targetSlot + " and no durable nonce replay service is available");
            }
            log.warn("No in-memory nonce checkpoint found for rollback to slot {}; repairing from durable state",
                    targetSlot);
            replayService.repairToBodyTip(nonceState, "rollback");
        }
    }
}
