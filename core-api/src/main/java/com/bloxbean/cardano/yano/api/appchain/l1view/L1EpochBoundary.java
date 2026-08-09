package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Arrays;
import java.util.Objects;

/** Exact first-applied-block identity for an L1 epoch transition. */
public record L1EpochBoundary(long previousEpoch,
                              long newEpoch,
                              long boundarySlot,
                              byte[] boundaryBlockHash,
                              long boundaryBlockNumber) {
    public L1EpochBoundary {
        if (previousEpoch < 0 || newEpoch <= previousEpoch) {
            throw new IllegalArgumentException("Invalid L1 epoch transition");
        }
        if (boundarySlot < 0 || boundaryBlockNumber < 0) {
            throw new IllegalArgumentException("Invalid L1 epoch boundary position");
        }
        Objects.requireNonNull(boundaryBlockHash, "boundaryBlockHash");
        if (boundaryBlockHash.length != 32) {
            throw new IllegalArgumentException("L1 epoch boundary block hash must be 32 bytes");
        }
        boundaryBlockHash = boundaryBlockHash.clone();
    }

    @Override
    public byte[] boundaryBlockHash() {
        return boundaryBlockHash.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof L1EpochBoundary that
                && previousEpoch == that.previousEpoch
                && newEpoch == that.newEpoch
                && boundarySlot == that.boundarySlot
                && boundaryBlockNumber == that.boundaryBlockNumber
                && Arrays.equals(boundaryBlockHash, that.boundaryBlockHash);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(previousEpoch, newEpoch, boundarySlot, boundaryBlockNumber);
        return 31 * result + Arrays.hashCode(boundaryBlockHash);
    }
}
