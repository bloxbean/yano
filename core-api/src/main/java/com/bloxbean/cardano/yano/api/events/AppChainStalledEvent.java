package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;

/**
 * Event published when the app chain appears stalled: a peer advertises a
 * higher finalized tip but this node has made no progress within the
 * liveness window (ops alerting).
 */
public final class AppChainStalledEvent implements Event {
    private final String chainId;
    private final long localTip;
    private final long bestPeerTip;

    public AppChainStalledEvent(String chainId, long localTip, long bestPeerTip) {
        this.chainId = chainId;
        this.localTip = localTip;
        this.bestPeerTip = bestPeerTip;
    }

    public String chainId() {
        return chainId;
    }

    public long localTip() {
        return localTip;
    }

    public long bestPeerTip() {
        return bestPeerTip;
    }
}
