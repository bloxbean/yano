package com.bloxbean.cardano.yano.runtime.peer;

/**
 * Reason a peer session recovery attempt was requested.
 */
public enum PeerRecoveryReason {
    UNKNOWN,
    MANUAL,
    NO_PROGRESS,
    KEEPALIVE_STALE,
    DISCONNECT_STALE,
    FAILED_WRITE,
    BODY_FETCH_STUCK,
    INTERSECTION_FAILED,
    STARTUP_FAILED,
    TERMINAL_FAILURE
}
