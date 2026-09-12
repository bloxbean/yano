package org.yanoproject.p2p.peer;

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
