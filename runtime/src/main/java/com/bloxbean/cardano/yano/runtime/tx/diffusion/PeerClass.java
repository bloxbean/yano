package com.bloxbean.cardano.yano.runtime.tx.diffusion;

/**
 * Coarse trust and direction classification for tx diffusion peers.
 */
public enum PeerClass {
    ACTIVE_SELECTED(true),
    TRUSTED_HOT(true),
    UNTRUSTED_HOT(false),
    DOWNSTREAM(false);

    private final boolean trusted;

    PeerClass(boolean trusted) {
        this.trusted = trusted;
    }

    public boolean trusted() {
        return trusted;
    }
}
