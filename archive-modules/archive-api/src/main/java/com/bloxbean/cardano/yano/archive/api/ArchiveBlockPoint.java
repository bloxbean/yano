package com.bloxbean.cardano.yano.archive.api;

import java.util.Arrays;

/** Canonical block coordinate used as an archive read boundary. */
public record ArchiveBlockPoint(long blockNumber, long slot, byte[] blockHash) {
    public ArchiveBlockPoint {
        if (blockNumber < 0 || slot < 0 || blockHash == null || blockHash.length == 0) {
            throw new IllegalArgumentException("invalid archive block point");
        }
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
    }

    @Override public byte[] blockHash() { return Arrays.copyOf(blockHash, blockHash.length); }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ArchiveBlockPoint point
                && blockNumber == point.blockNumber && slot == point.slot
                && Arrays.equals(blockHash, point.blockHash);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(blockNumber);
        result = 31 * result + Long.hashCode(slot);
        return 31 * result + Arrays.hashCode(blockHash);
    }
}
