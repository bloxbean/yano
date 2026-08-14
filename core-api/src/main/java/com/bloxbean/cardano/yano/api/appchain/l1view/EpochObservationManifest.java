package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Arrays;
import java.util.Objects;

/**
 * Deterministic first-pass result for one epoch observer. The observer writes
 * one manifest observation followed by exactly {@code chunkCount} data chunks.
 */
public record EpochObservationManifest(int version,
                                       String observerId,
                                       long previousEpoch,
                                       long newEpoch,
                                       long datasetEpoch,
                                       long totalEntries,
                                       int chunkEntries,
                                       int chunkCount,
                                       byte[] snapshotRoot) {
    public static final int VERSION = 1;

    public EpochObservationManifest {
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported epoch observation manifest version");
        }
        Objects.requireNonNull(observerId, "observerId");
        if (observerId.isBlank()) {
            throw new IllegalArgumentException("observerId must not be blank");
        }
        if (previousEpoch < 0 || newEpoch <= previousEpoch || datasetEpoch < 0
                || totalEntries < 0 || chunkEntries < 0 || chunkCount < 0) {
            throw new IllegalArgumentException("Invalid epoch observation manifest bounds");
        }
        if (totalEntries > 0 && chunkEntries <= 0) {
            throw new IllegalArgumentException("Non-empty datasets require chunkEntries > 0");
        }
        Objects.requireNonNull(snapshotRoot, "snapshotRoot");
        if (snapshotRoot.length != 32) {
            throw new IllegalArgumentException("Epoch snapshot root must be 32 bytes");
        }
        snapshotRoot = snapshotRoot.clone();
    }

    public int observationCount() {
        return Math.addExact(chunkCount, 1);
    }

    @Override
    public byte[] snapshotRoot() {
        return snapshotRoot.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EpochObservationManifest that
                && version == that.version
                && previousEpoch == that.previousEpoch
                && newEpoch == that.newEpoch
                && datasetEpoch == that.datasetEpoch
                && totalEntries == that.totalEntries
                && chunkEntries == that.chunkEntries
                && chunkCount == that.chunkCount
                && observerId.equals(that.observerId)
                && Arrays.equals(snapshotRoot, that.snapshotRoot);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(version, observerId, previousEpoch, newEpoch,
                datasetEpoch, totalEntries, chunkEntries, chunkCount);
        return 31 * result + Arrays.hashCode(snapshotRoot);
    }
}
