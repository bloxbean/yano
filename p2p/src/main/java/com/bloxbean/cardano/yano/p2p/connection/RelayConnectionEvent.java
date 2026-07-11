package com.bloxbean.cardano.yano.p2p.connection;

public record RelayConnectionEvent(
        String connectionId,
        ConnectionDirection direction,
        ConnectionState state,
        ConnectionKey key,
        ProtocolCapabilities capabilities,
        String reason,
        long timestampMillis) {
}
