package com.bloxbean.cardano.yano.p2p.connection;

/**
 * Immutable per-connection view for relay governance and diagnostics.
 */
public record RelayConnectionInfo(
        String connectionId,
        ConnectionDirection direction,
        ConnectionState state,
        ConnectionKey key,
        ProtocolCapabilities capabilities,
        String reason,
        long createdAtMillis,
        long updatedAtMillis) {

    public boolean established() {
        return state == ConnectionState.ESTABLISHED;
    }

    public boolean outbound() {
        return direction == ConnectionDirection.OUTBOUND;
    }

    public boolean usableForPeerSharing() {
        return established() && capabilities != null && capabilities.peerSharing();
    }
}
