package com.bloxbean.cardano.yano.p2p.tx.diffusion;

public record TxDiffusionStats(
        String mode,
        boolean enabled,
        int peerCount,
        long acceptedMempoolEvents,
        long inboundTxIdsRequested,
        long inboundTxIdsRejected,
        long inboundTxIdsIgnored,
        long inboundTxBodiesAccepted,
        long inboundTxBodiesRejected,
        long inboundTxBodiesIgnored,
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
                0,
                0,
                0,
                0);
    }
}
