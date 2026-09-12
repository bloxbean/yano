package org.yanoproject.p2p.governor;

public enum PeerState {
    COLD,
    WARM,
    HOT,
    BACKOFF,
    QUARANTINED
}
