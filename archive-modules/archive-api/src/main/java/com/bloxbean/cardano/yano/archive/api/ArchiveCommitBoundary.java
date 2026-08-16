package com.bloxbean.cardano.yano.archive.api;

import java.util.Objects;

/** A committed, canonical range endpoint visible in one pinned archive snapshot. */
public record ArchiveCommitBoundary(
        ArchiveDatasetId dataset,
        int projectionVersion,
        BlockRange range,
        ArchiveRangeAnchor anchors,
        long backendGeneration) {
    public ArchiveCommitBoundary {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(anchors, "anchors");
        if (dataset.sourceKind() != SourceKind.BLOCK || projectionVersion < 1 || backendGeneration < 0) {
            throw new IllegalArgumentException("invalid block archive boundary");
        }
    }
}
