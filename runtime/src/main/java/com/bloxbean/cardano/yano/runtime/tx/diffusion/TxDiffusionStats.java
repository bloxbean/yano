package com.bloxbean.cardano.yano.runtime.tx.diffusion;

public record TxDiffusionStats(
        String mode,
        boolean enabled,
        int peerCount,
        long acceptedMempoolEvents,
        long inboundAccepted,
        long inboundRejected,
        long inboundIgnored,
        long outboundForwarded,
        long outboundSuppressed,
        long servedTxs,
        long servedBytes,
        long inFlightTxs,
        long inFlightBytes
) {
    public static TxDiffusionStats disabled() {
        return new TxDiffusionStats(
                TxDiffusionMode.DISABLED.configValue(),
                false,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }
}
