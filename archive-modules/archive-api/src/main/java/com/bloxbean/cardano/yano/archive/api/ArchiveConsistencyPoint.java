package com.bloxbean.cardano.yano.archive.api;

import java.util.Map;
import java.util.Objects;

/** Finalized range that is complete for every selected dataset in one backend generation. */
public record ArchiveConsistencyPoint(
        long generation,
        BlockRange completeRange,
        ArchiveBlockPoint asOf,
        Map<ArchiveDatasetId, Integer> projectionVersions) {
    public ArchiveConsistencyPoint {
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        Objects.requireNonNull(completeRange, "completeRange");
        Objects.requireNonNull(asOf, "asOf");
        projectionVersions = Map.copyOf(Objects.requireNonNull(projectionVersions, "projectionVersions"));
        if (projectionVersions.isEmpty() || completeRange.endInclusive() != asOf.blockNumber()
                || projectionVersions.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().sourceKind() != SourceKind.BLOCK || entry.getValue() == null
                || entry.getValue() < 1)) {
            throw new IllegalArgumentException("invalid archive consistency point");
        }
    }
}
