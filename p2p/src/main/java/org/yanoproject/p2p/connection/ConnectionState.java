package org.yanoproject.p2p.connection;

public enum ConnectionState {
    CONNECTING,
    HANDSHAKING,
    ESTABLISHED,
    FAILED,
    CLOSED
}
