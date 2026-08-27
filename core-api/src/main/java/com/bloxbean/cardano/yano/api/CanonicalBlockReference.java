package com.bloxbean.cardano.yano.api;

import java.util.Arrays;

/** Canonical chain coordinate used to bind asynchronous derived work. */
public record CanonicalBlockReference(long blockNumber, long slot, byte[] blockHash) {
    public CanonicalBlockReference {
        if (blockNumber < 0) {
            throw new IllegalArgumentException("blockNumber must be non-negative");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be non-negative");
        }
        if (blockHash == null || blockHash.length == 0) {
            throw new IllegalArgumentException("blockHash must not be empty");
        }
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
    }

    @Override
    public byte[] blockHash() {
        return Arrays.copyOf(blockHash, blockHash.length);
    }
}
