package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.Objects;

/** Deterministic source boundary bound into an authenticated snapshot. */
public sealed interface SnapshotSourceBoundary {
    record AppHeight(long appHeight) implements SnapshotSourceBoundary {
        public AppHeight {
            if (appHeight < 0) throw new IllegalArgumentException("appHeight must be nonnegative");
        }
    }

    record L1Epoch(long previousEpoch, long newEpoch, long datasetEpoch,
                   long boundarySlot, byte[] blockHash) implements SnapshotSourceBoundary {
        public L1Epoch {
            if (previousEpoch < 0 || newEpoch < 0 || datasetEpoch < 0 || boundarySlot < 0) {
                throw new IllegalArgumentException("epoch boundary fields must be nonnegative");
            }
            blockHash = Objects.requireNonNull(blockHash, "blockHash").clone();
            if (blockHash.length != 32) throw new IllegalArgumentException("blockHash must be 32 bytes");
        }

        @Override public byte[] blockHash() { return blockHash.clone(); }
    }
}
