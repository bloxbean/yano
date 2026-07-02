package com.bloxbean.cardano.yano.p2p.governor;

import java.util.List;

public record PeerGovernorSnapshot(
        int knownPeerCount,
        int coldPeerCount,
        int warmPeerCount,
        int hotPeerCount,
        int backoffPeerCount,
        int quarantinedPeerCount,
        int sharablePeerCount,
        int inboundPeerCount,
        int gossipPeerCount,
        int ledgerPeerCount,
        int bootstrapPeerCount,
        int targetKnownPeers,
        int targetWarmPeers,
        int targetHotPeers,
        long lastReconcileAtMillis,
        List<PeerDescriptor> peers,
        List<PeerGovernorPeerInfo> peerInfos) {

    public PeerGovernorSnapshot {
        peers = peers != null ? List.copyOf(peers) : List.of();
        peerInfos = peerInfos != null ? List.copyOf(peerInfos) : List.of();
    }

    public static PeerGovernorSnapshot empty(int targetKnownPeers, int targetWarmPeers, int targetHotPeers) {
        return new PeerGovernorSnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                targetKnownPeers, targetWarmPeers, targetHotPeers, 0L, List.of(), List.of());
    }
}
