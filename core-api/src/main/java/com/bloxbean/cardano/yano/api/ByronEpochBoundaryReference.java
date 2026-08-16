package com.bloxbean.cardano.yano.api;

import java.util.Arrays;

/**
 * Canonical Byron epoch-boundary block identity at an epoch-start slot.
 *
 * <p>Epoch-boundary blocks are deliberately not assigned a main-chain block
 * number. They may nevertheless bridge the parent relation between two
 * numbered Byron blocks at the same slot.</p>
 */
public record ByronEpochBoundaryReference(long slot, byte[] blockHash, byte[] parentHash) {
    public ByronEpochBoundaryReference {
        if (slot < 0) throw new IllegalArgumentException("slot must be non-negative");
        if (blockHash == null || blockHash.length == 0) throw new IllegalArgumentException("blockHash is required");
        if (parentHash == null || parentHash.length == 0) throw new IllegalArgumentException("parentHash is required");
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
        parentHash = Arrays.copyOf(parentHash, parentHash.length);
    }

    @Override
    public byte[] blockHash() {
        return Arrays.copyOf(blockHash, blockHash.length);
    }

    @Override
    public byte[] parentHash() {
        return Arrays.copyOf(parentHash, parentHash.length);
    }
}
