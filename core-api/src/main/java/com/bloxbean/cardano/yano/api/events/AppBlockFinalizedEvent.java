package com.bloxbean.cardano.yano.api.events;

import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;

/**
 * Event published when an app block reaches APP_FINAL: its finality
 * certificate met the threshold and the block was committed atomically
 * with the state trie and root.
 */
public final class AppBlockFinalizedEvent implements Event {
    private final AppBlock block;
    private final byte[] blockHash;

    public AppBlockFinalizedEvent(AppBlock block, byte[] blockHash) {
        this.block = block;
        this.blockHash = blockHash;
    }

    public AppBlock block() {
        return block;
    }

    public byte[] blockHash() {
        return blockHash;
    }
}
