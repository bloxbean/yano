package com.bloxbean.cardano.yano.archive.core.hot;

import java.util.Arrays;

public record HotBlockCheckpoint(long blockNumber, long slot, byte[] blockHash, byte[] parentHash) {
    public HotBlockCheckpoint {
        if (blockNumber < 0 || slot < 0 || blockHash == null || blockHash.length == 0 || parentHash == null) {
            throw new IllegalArgumentException("invalid hot block checkpoint");
        }
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
        parentHash = Arrays.copyOf(parentHash, parentHash.length);
    }
    @Override public byte[] blockHash() { return Arrays.copyOf(blockHash, blockHash.length); }
    @Override public byte[] parentHash() { return Arrays.copyOf(parentHash, parentHash.length); }
}
