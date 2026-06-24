package com.bloxbean.cardano.yano.runtime.chain;

/**
 * Stores Byron epoch-boundary headers without treating them as regular blocks.
 */
public interface ByronEbHeaderStore {
    void storeByronEbHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader);
}
