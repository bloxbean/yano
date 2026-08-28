package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Canonical, query-visible evidence that one selected artifact epoch was lost. */
public record EpochArtifactGap(
        ArchiveDatasetId dataset,
        int semanticEpoch,
        long boundaryBlockNumber,
        long boundarySlot,
        byte[] boundaryBlockHash,
        String failureClass,
        String detail,
        Instant recordedAt) {

    public EpochArtifactGap {
        Objects.requireNonNull(dataset, "dataset");
        if (semanticEpoch < 0 || boundaryBlockNumber < 0 || boundarySlot < 0) {
            throw new IllegalArgumentException("gap epoch and canonical coordinates must be non-negative");
        }
        Objects.requireNonNull(boundaryBlockHash, "boundaryBlockHash");
        if (boundaryBlockHash.length == 0) {
            throw new IllegalArgumentException("gap boundary hash is required");
        }
        boundaryBlockHash = Arrays.copyOf(boundaryBlockHash, boundaryBlockHash.length);
        failureClass = bounded(Objects.requireNonNull(failureClass, "failureClass"), 80);
        detail = bounded(detail == null ? "" : detail, 1_024);
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    @Override
    public byte[] boundaryBlockHash() {
        return Arrays.copyOf(boundaryBlockHash, boundaryBlockHash.length);
    }

    public boolean sameOutcome(EpochArtifactGap other) {
        return dataset == other.dataset && semanticEpoch == other.semanticEpoch
                && boundaryBlockNumber == other.boundaryBlockNumber
                && boundarySlot == other.boundarySlot
                && Arrays.equals(boundaryBlockHash, other.boundaryBlockHash)
                && failureClass.equals(other.failureClass);
    }

    private static String bounded(String value, int max) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
