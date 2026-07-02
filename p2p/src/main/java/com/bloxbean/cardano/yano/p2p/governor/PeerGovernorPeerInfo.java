package com.bloxbean.cardano.yano.p2p.governor;

/**
 * Immutable per-peer governor view for diagnostics.
 */
public record PeerGovernorPeerInfo(
        PeerDescriptor descriptor,
        PeerState state,
        long backoffUntilMillis) {
}
