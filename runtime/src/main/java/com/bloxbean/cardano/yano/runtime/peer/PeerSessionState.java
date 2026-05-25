package com.bloxbean.cardano.yano.runtime.peer;

/**
 * Lifecycle state for the active upstream peer session.
 */
public enum PeerSessionState {
    NEW,
    STARTING,
    RUNNING,
    RECOVERING,
    STOPPING,
    STOPPED,
    TERMINAL_FAILURE
}
