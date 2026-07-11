package com.bloxbean.cardano.yano.p2p.governor;

public enum PeerState {
    COLD,
    WARM,
    HOT,
    BACKOFF,
    QUARANTINED
}
