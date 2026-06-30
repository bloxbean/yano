package com.bloxbean.cardano.yano.runtime.connection;

import java.util.List;

public record RelayConnectionSnapshot(
        int inboundConnectionCount,
        int outboundConnectionCount,
        int establishedConnectionCount,
        int connectingConnectionCount,
        long rejectedInboundConnections,
        long failedOutboundConnections,
        int connectionsPerIpMax,
        List<RelayConnectionInfo> connections) {
    public RelayConnectionSnapshot {
        connections = connections != null ? List.copyOf(connections) : List.of();
    }
}
