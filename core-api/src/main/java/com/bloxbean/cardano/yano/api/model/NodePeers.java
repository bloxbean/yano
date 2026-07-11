package com.bloxbean.cardano.yano.api.model;

import java.util.List;

/**
 * Sanitized peer table snapshot for operator APIs and embedded status UI.
 */
public record NodePeers(
        long timestamp,
        String activePeerId,
        String activePeerName,
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
        int inboundConnectionCount,
        int outboundConnectionCount,
        int establishedConnectionCount,
        int connectingConnectionCount,
        long rejectedInboundConnections,
        long failedOutboundConnections,
        List<NodePeer> peers) {

    public NodePeers {
        peers = peers != null ? List.copyOf(peers) : List.of();
    }

    public record NodePeer(
            String id,
            String host,
            int port,
            String endpoint,
            String source,
            String sourceId,
            Boolean trusted,
            Boolean advertise,
            Boolean sharable,
            Integer score,
            String governorState,
            Long firstSeenMillis,
            Long lastSeenMillis,
            Long expiresAtMillis,
            Long backoffUntilMillis,
            String connectionId,
            String direction,
            String connectionState,
            String connectionReason,
            Long connectionCreatedAtMillis,
            Long connectionUpdatedAtMillis,
            Long negotiatedVersion,
            Boolean chainSync,
            Boolean blockFetch,
            Boolean txSubmission,
            Boolean keepAlive,
            Boolean peerSharing,
            Boolean query,
            boolean active,
            boolean established) {
    }
}
