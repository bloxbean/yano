package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared nonce-evolution logic for live block events and startup/rollback
 * replay. Keeping the formula in one place prevents the replay path from
 * drifting from normal sync behavior.
 */
@Slf4j(topic = "com.bloxbean.cardano.yano.runtime.blockproducer.NonceEvolutionListener")
public final class EpochNonceEvolver {

    private static final long MAINNET_PROTOCOL_MAGIC = 764824073L;
    private static final String MAINNET_EPOCH_259_ENTROPY_HEX =
            "d982e06fd33e7440b43cefad529b7ecafbaa255e38178ad4189a37e4ce9bf1fa";

    private final EpochParamProvider paramProvider;
    private final boolean trackedEpochParamsAvailable;
    private final long protocolMagic;
    private boolean customNetworkWarned;

    public EpochNonceEvolver(EpochParamProvider paramProvider,
                             boolean trackedEpochParamsAvailable,
                             long protocolMagic) {
        this.paramProvider = paramProvider;
        this.trackedEpochParamsAvailable = trackedEpochParamsAvailable;
        this.protocolMagic = protocolMagic;
    }

    public EvolutionResult evolve(EpochNonceState nonceState, Era era, long slot, HeaderBody hb) {
        if (hb == null) {
            return EvolutionResult.skipped("missing_header_body");
        }

        VrfCert vrfCert = hb.getVrfResult();
        if (vrfCert == null) {
            vrfCert = hb.getNonceVrf();
        }
        if (vrfCert == null || vrfCert.get_1() == null) {
            return EvolutionResult.skipped("missing_vrf");
        }

        byte[] vrfOutput = HexUtil.decodeHexString(vrfCert.get_1());
        byte[] prevHash = hb.getPrevHash() != null
                ? HexUtil.decodeHexString(hb.getPrevHash())
                : null;

        if (era != Era.Byron && !nonceState.isShelleyStartSlotSet()) {
            nonceState.setShelleyStartSlot(slot);
        }

        if (log.isDebugEnabled() && slot < 500) {
            String vrfSource = hb.getVrfResult() != null ? "vrfResult" : "nonceVrf";
            log.debug("NonceEvolve BEFORE slot={} era={} vrfSource={} vrfOutput={} prevHash={}",
                    slot, era, vrfSource,
                    HexUtil.encodeHexString(vrfOutput).substring(0, Math.min(32, vrfOutput.length * 2)),
                    prevHash != null ? HexUtil.encodeHexString(prevHash).substring(0, 16) : "null");
        }

        int epochBefore = nonceState.getCurrentEpoch();
        int blockEpoch = nonceState.epochForSlot(slot);
        boolean crossingBoundary = blockEpoch > epochBefore;
        byte[] extraEntropy = resolveExtraEntropyAtBoundary(crossingBoundary, blockEpoch, era);

        if (crossingBoundary) {
            nonceState.advanceEpochIfNeeded(slot, extraEntropy, era);
        } else {
            nonceState.advanceEpochIfNeeded(slot);
        }

        int epochAfter = nonceState.getCurrentEpoch();
        nonceState.onBlockObserved(slot, prevHash, vrfOutput, era);

        if (log.isDebugEnabled() && slot < 500) {
            log.debug("NonceEvolve AFTER  slot={} epoch={} evolvingNonce={} candidateNonce={}",
                    slot, nonceState.getCurrentEpoch(),
                    HexUtil.encodeHexString(nonceState.getEvolvingNonce()).substring(0, 16),
                    HexUtil.encodeHexString(nonceState.getCandidateNonce()).substring(0, 16));
        }

        // A pending marker can be left by older producer-side leader checks. Body replay must
        // not turn that stale producer marker into a durable epoch checkpoint at the wrong
        // block cursor; only a boundary crossed by this observed block is checkpoint-worthy.
        nonceState.consumeEpochTransitionPending();
        boolean checkpointBoundary = epochAfter > epochBefore;
        return new EvolutionResult(true, null, epochBefore, epochAfter, checkpointBoundary);
    }

    private byte[] resolveExtraEntropyAtBoundary(boolean crossingBoundary, int blockEpoch, Era era) {
        if (!crossingBoundary) return null;

        if (era == null) {
            log.warn("Epoch boundary crossed at epoch {} with unknown era; "
                    + "skipping TPraos extraEntropy combine", blockEpoch);
            return null;
        }

        if (!EpochNonceState.usesTPraosExtraEntropy(era)) {
            return null;
        }

        String raw = paramProvider != null ? paramProvider.getExtraEntropy(blockEpoch) : null;
        byte[] extraEntropy = decodeExtraEntropy(raw, blockEpoch);

        if (protocolMagic == MAINNET_PROTOCOL_MAGIC && blockEpoch == 259 && extraEntropy == null) {
            log.warn("Mainnet epoch 259 extraEntropy is missing; epoch nonce will diverge "
                    + "from Haskell. Expected entropy: " + MAINNET_EPOCH_259_ENTROPY_HEX);
        }

        if (!trackedEpochParamsAvailable && !customNetworkWarned
                && protocolMagic != MAINNET_PROTOCOL_MAGIC) {
            log.warn("First TPraos boundary at epoch {} crossed without EpochParamTracker. "
                    + "If this network has on-chain extraEntropy updates, the epoch nonce "
                    + "will diverge from Haskell.", blockEpoch);
            customNetworkWarned = true;
        }

        return extraEntropy;
    }

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

    public record EvolutionResult(
            boolean applied,
            String skipReason,
            int epochBefore,
            int epochAfter,
            boolean epochTransition) {

        static EvolutionResult skipped(String reason) {
            return new EvolutionResult(false, reason, -1, -1, false);
        }
    }
}
