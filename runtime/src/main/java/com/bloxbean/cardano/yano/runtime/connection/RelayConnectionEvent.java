package com.bloxbean.cardano.yano.runtime.connection;

public record RelayConnectionEvent(
        String connectionId,
        ConnectionDirection direction,
        ConnectionState state,
        ConnectionKey key,
        ProtocolCapabilities capabilities,
        String reason,
        long timestampMillis) {
}
