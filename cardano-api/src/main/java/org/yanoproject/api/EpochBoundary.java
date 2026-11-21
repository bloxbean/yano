package org.yanoproject.api;

import java.util.Arrays;
import java.util.Objects;

/** Exact first-applied-block identity of a completed ledger epoch transition. */
public record EpochBoundary(long previousEpoch,
                            long newEpoch,
                            long slot,
                            byte[] blockHash,
                            long blockNumber) {
    public EpochBoundary {
        if (previousEpoch < 0 || newEpoch <= previousEpoch) {
            throw new IllegalArgumentException("Invalid epoch transition");
        }
        if (slot < 0 || blockNumber < 0) {
            throw new IllegalArgumentException("Invalid epoch boundary position");
        }
        Objects.requireNonNull(blockHash, "blockHash");
        if (blockHash.length != 32) {
            throw new IllegalArgumentException("Epoch boundary block hash must be 32 bytes");
        }
        blockHash = blockHash.clone();
    }

    @Override
    public byte[] blockHash() {
        return blockHash.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EpochBoundary that
                && previousEpoch == that.previousEpoch
                && newEpoch == that.newEpoch
                && slot == that.slot
                && blockNumber == that.blockNumber
                && Arrays.equals(blockHash, that.blockHash);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(previousEpoch, newEpoch, slot, blockNumber);
        return 31 * result + Arrays.hashCode(blockHash);
    }
}
