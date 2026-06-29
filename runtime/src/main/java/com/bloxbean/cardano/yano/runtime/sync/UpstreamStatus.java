package com.bloxbean.cardano.yano.runtime.sync;

import com.bloxbean.cardano.yano.api.config.UpstreamPreset;

/**
 * Runtime upstream-controller status.
 */
public record UpstreamStatus(
        UpstreamPreset mode,
        int configuredPeerCount,
        int hotPeerCount,
        int observerPeerCount,
        int knownPeerCount,
        int candidateHeaderCount,
        String activePeerId,
        String activePeerName,
        String txForwarding,
        boolean multiPeerObservationOnly,
        boolean discoveryRunning,
        String validationLevel,
        long validationAcceptedHeaders,
        long validationRejectedHeaders,
        String validationLastRejectedStage,
        String validationLastRejectedReason
) {
    public static UpstreamStatus idle(UpstreamPreset mode, int configuredPeerCount, String txForwarding) {
        return new UpstreamStatus(
                mode != null ? mode : UpstreamPreset.TRUSTED_SINGLE,
                Math.max(0, configuredPeerCount),
                0,
                0,
                Math.max(0, configuredPeerCount),
                0,
                null,
                null,
                txForwarding != null ? txForwarding : "active-selected",
                false,
                false,
                "none",
                0,
                0,
                null,
                null);
    }
}
