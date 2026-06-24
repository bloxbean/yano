package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;

/**
 * Chain storage and event access for consumers that do not need devnet or
 * producer controls.
 */
public interface ChainQuery extends ChainBlockReader {
    byte[] getBlock(byte[] blockHash);

    boolean recoverChain();

    void addBlockChainDataListener(BlockChainDataListener listener);

    void removeBlockChainDataListener(BlockChainDataListener listener);

    void registerListeners(Object... listeners);

    void registerListener(Object listener, SubscriptionOptions sbOptions);
}
