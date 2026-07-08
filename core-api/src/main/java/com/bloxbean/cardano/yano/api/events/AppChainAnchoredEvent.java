package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Event published when an anchor transaction for the app chain has been
 * observed in an applied L1 block (finality level L1_ANCHORED).
 */
public final class AppChainAnchoredEvent implements Event {
    private final String chainId;
    private final long fromHeight;
    private final long toHeight;
    private final String anchorTxHash;
    private final long l1Slot;

    public AppChainAnchoredEvent(String chainId, long fromHeight, long toHeight,
                                 String anchorTxHash, long l1Slot) {
        this.chainId = chainId;
        this.fromHeight = fromHeight;
        this.toHeight = toHeight;
        this.anchorTxHash = anchorTxHash;
        this.l1Slot = l1Slot;
    }

    public String chainId() {
        return chainId;
    }

    public long fromHeight() {
        return fromHeight;
    }

    public long toHeight() {
        return toHeight;
    }

    public String anchorTxHash() {
        return anchorTxHash;
    }

    public long l1Slot() {
        return l1Slot;
    }
}
