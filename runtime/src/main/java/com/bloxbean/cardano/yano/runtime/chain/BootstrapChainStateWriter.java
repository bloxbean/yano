package com.bloxbean.cardano.yano.runtime.chain;

/**
 * Writes synthetic chain entries during external-state bootstrap.
 */
public interface BootstrapChainStateWriter {
    void forceStoreBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader);

    void forceStoreBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block);
}
