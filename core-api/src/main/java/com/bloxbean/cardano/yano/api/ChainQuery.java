package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;

/**
 * Chain storage and event access for consumers that do not need devnet or
 * producer controls.
 */
public interface ChainQuery extends ChainBlockReader {
    /** Best known upstream target height; empty for producer/offline modes. */
    default java.util.OptionalLong getSyncTargetBlockNumber() {
        return java.util.OptionalLong.empty();
    }
    byte[] getBlock(byte[] blockHash);

    boolean recoverChain();

    void addBlockChainDataListener(BlockChainDataListener listener);

    void removeBlockChainDataListener(BlockChainDataListener listener);

    void registerListeners(Object... listeners);

    void registerListener(Object listener, SubscriptionOptions sbOptions);

    /**
     * Installs an optional derived-data low watermark before block pruning
     * starts. Core sync never waits for the consumer; pruning only observes
     * its oldest durable source lease.
     */
    default void setBlockBodyRetentionBoundary(BlockBodyRetentionBoundary boundary) {
        throw new UnsupportedOperationException("block-body retention boundary is unavailable");
    }
}
