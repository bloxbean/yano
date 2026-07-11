package com.bloxbean.cardano.yano.p2p.connection;

public enum ConnectionState {
    CONNECTING,
    HANDSHAKING,
    ESTABLISHED,
    FAILED,
    CLOSED
}
