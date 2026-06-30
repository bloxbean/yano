package com.bloxbean.cardano.yano.runtime.sync.multipeer;

public enum PeerState {
    COLD,
    WARM,
    HOT,
    BACKOFF,
    QUARANTINED
}
