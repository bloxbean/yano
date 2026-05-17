package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
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

    private static final long MAINNET_PROTOCOL_MAGIC = 764824073L;
    private static final String MAINNET_EPOCH_259_ENTROPY_HEX =
            "d982e06fd33e7440b43cefad529b7ecafbaa255e38178ad4189a37e4ce9bf1fa";

    private final EpochNonceState nonceState;
    private final NonceStateStore nonceStore;  // nullable
    private final String ownIssuerVkey;        // hex, to skip own produced blocks (nullable)
    private final EpochParamProvider paramProvider; // nullable
    private final boolean trackedEpochParamsAvailable;
    private final long protocolMagic;

    private boolean customNetworkWarned;

    /**
     * Backward-compatible constructor used by tests / paths that do not need TPraos extraEntropy.
     */
    public NonceEvolutionListener(EpochNonceState nonceState, NonceStateStore nonceStore,
                                   String ownIssuerVkey) {
        this(nonceState, nonceStore, ownIssuerVkey, null, false, 0L);
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
        this.nonceState = nonceState;
        this.nonceStore = nonceStore;
        this.ownIssuerVkey = ownIssuerVkey;
        this.paramProvider = paramProvider;
        this.trackedEpochParamsAvailable = trackedEpochParamsAvailable;
        this.protocolMagic = protocolMagic;
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

        // Extract VRF output: Babbage+ uses vrfResult, pre-Babbage uses nonceVrf
        VrfCert vrfCert = hb.getVrfResult();
        if (vrfCert == null) {
            vrfCert = hb.getNonceVrf();
        }
        if (vrfCert == null || vrfCert.get_1() == null) return;

        byte[] vrfOutput = HexUtil.decodeHexString(vrfCert.get_1());
        byte[] prevHash = hb.getPrevHash() != null
                ? HexUtil.decodeHexString(hb.getPrevHash()) : null;
        long slot = event.slot();

        // Detect Shelley start slot from first non-Byron era block
        if (era != Era.Byron && !nonceState.isShelleyStartSlotSet()) {
            nonceState.setShelleyStartSlot(slot);
        }

        // Debug logging for first blocks to trace VRF data flow
        if (log.isDebugEnabled() && slot < 500) {
            String vrfSource = hb.getVrfResult() != null ? "vrfResult" : "nonceVrf";
            log.debug("NonceEvolve BEFORE slot={} era={} vrfSource={} vrfOutput={} prevHash={}",
                    slot, era, vrfSource,
                    HexUtil.encodeHexString(vrfOutput).substring(0, Math.min(32, vrfOutput.length * 2)),
                    prevHash != null ? HexUtil.encodeHexString(prevHash).substring(0, 16) : "null");
        }

        // Resolve TPraos extraEntropy + targeted WARNs if we're about to cross an epoch boundary
        int epochBefore = nonceState.getCurrentEpoch();
        int blockEpoch = nonceState.epochForSlot(slot);
        boolean crossingBoundary = blockEpoch > epochBefore;

        byte[] extraEntropy = resolveExtraEntropyAtBoundary(crossingBoundary, blockEpoch, era);

        // Evolve nonce state (era-aware)
        if (crossingBoundary) {
            nonceState.advanceEpochIfNeeded(slot, extraEntropy, era);
        } else {
            nonceState.advanceEpochIfNeeded(slot);
        }
        int epochAfter = nonceState.getCurrentEpoch();
        nonceState.onBlockObserved(slot, prevHash, vrfOutput, era);

        // Debug logging after evolution
        if (log.isDebugEnabled() && slot < 500) {
            log.debug("NonceEvolve AFTER  slot={} epoch={} evolvingNonce={} candidateNonce={}",
                    slot, nonceState.getCurrentEpoch(),
                    HexUtil.encodeHexString(nonceState.getEvolvingNonce()).substring(0, 16),
                    HexUtil.encodeHexString(nonceState.getCandidateNonce()).substring(0, 16));
        }

        // Log epoch transition with the new epoch nonce
        if (epochAfter > epochBefore) {
            byte[] epochNonce = nonceState.getEpochNonce();
            if (nonceStore != null) {
                nonceStore.storeEpochNonce(epochAfter, epochNonce);
            }
            log.info("Epoch nonce for epoch {}: {}",
                    epochAfter, epochNonce != null ? HexUtil.encodeHexString(epochNonce) : null);
        }

        // Serialize once, reuse for both checkpoint and persistence
        byte[] serialized = nonceState.serialize();
        nonceState.saveCheckpoint(slot, serialized);

        if (nonceStore != null) {
            nonceStore.storeEpochNonceState(serialized);
        }

        if (log.isTraceEnabled()) {
            log.trace("Nonce evolved at slot {} (epoch {}), evolvingNonce={}",
                    slot, nonceState.getCurrentEpoch(),
                    HexUtil.encodeHexString(nonceState.getEvolvingNonce()));
        }
    }

    /**
     * Resolve extraEntropy for the boundary and emit targeted WARNs.
     * Single unified TPraos branch — provider is queried/decoded exactly once per boundary.
     */
    private byte[] resolveExtraEntropyAtBoundary(boolean crossingBoundary, int blockEpoch, Era era) {
        if (!crossingBoundary) return null;

        if (era == null) {
            // Unknown era at a boundary is a data-resolution problem. Do NOT treat null as TPraos.
            // Fires regardless of paramProvider wiring.
            log.warn("Epoch boundary crossed at epoch {} with unknown era; "
                    + "skipping TPraos extraEntropy combine", blockEpoch);
            return null;
        }

        if (!EpochNonceState.usesTPraosExtraEntropy(era)) {
            // Babbage+: no query, no warn — entropy never applies in TICKN.
            return null;
        }

        String raw = paramProvider != null ? paramProvider.getExtraEntropy(blockEpoch) : null;
        byte[] extraEntropy = decodeExtraEntropy(raw, blockEpoch);

        // Targeted hard guard: mainnet's known non-null entropy epoch.
        // Fires regardless of trackedEpochParamsAvailable — a tracker wired but stale/missing
        // entry 259 is just as broken as no tracker.
        if (protocolMagic == MAINNET_PROTOCOL_MAGIC && blockEpoch == 259 && extraEntropy == null) {
            log.warn("Mainnet epoch 259 extraEntropy is missing; epoch nonce will diverge "
                    + "from Haskell. Expected entropy: " + MAINNET_EPOCH_259_ENTROPY_HEX);
        }

        // Custom-network heuristic: one-time-per-process WARN on first TPraos boundary
        // when no tracker is wired.
        if (!trackedEpochParamsAvailable && !customNetworkWarned
                && protocolMagic != MAINNET_PROTOCOL_MAGIC) {
            log.warn("First TPraos boundary at epoch {} crossed without EpochParamTracker. "
                    + "If this network has on-chain extraEntropy updates, the epoch nonce "
                    + "will diverge from Haskell.", blockEpoch);
            customNetworkWarned = true;
        }

        return extraEntropy;
    }

    /**
     * Defensive decoder around {@link HexUtil#decodeHexString(String)}. Trims, strips
     * optional {@code 0x}/{@code \x} prefixes, validates 32-byte length, catches decode
     * failures, and logs WARN. Never throws — returns {@code null} on any decode failure
     * so live sync is not blocked.
     */
    static byte[] decodeExtraEntropy(String raw, int epoch) {
        if (raw == null) return null;
        String hex = raw.trim();
        if (hex.isEmpty()) return null;
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        } else if (hex.startsWith("\\x") || hex.startsWith("\\X")) {
            hex = hex.substring(2);
        }
        if (hex.length() != 64) {
            log.warn("Malformed extraEntropy for epoch {}: expected 64 hex chars, got {}",
                    epoch, hex.length());
            return null;
        }
        try {
            return HexUtil.decodeHexString(hex);
        } catch (Exception e) {
            log.warn("Failed to decode extraEntropy hex for epoch {}: {}", epoch, e.getMessage());
            return null;
        }
    }

    @DomainEventListener(order = 50)
    public void onRollback(RollbackEvent event) {
        long targetSlot = event.target().getSlot();
        boolean restored = nonceState.rollbackTo(targetSlot);
        if (restored) {
            log.info("Nonce state rolled back to slot {}", targetSlot);
            if (nonceStore != null) {
                nonceStore.storeEpochNonceState(nonceState.serialize());
                nonceStore.pruneEpochNoncesAfter(nonceState.getCurrentEpoch());
            }
        } else {
            log.warn("No nonce checkpoint found for rollback to slot {}. "
                    + "Nonce state may be incorrect until next epoch boundary.", targetSlot);
        }
    }
}
