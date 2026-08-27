package com.bloxbean.cardano.yano.archive.api;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Explicit complete ranges; gaps are meaningful and never presented as empty history. */
public record ArchiveCoverage(ArchiveDatasetId dataset, int projectionVersion,
                              long revision, List<ArchiveRange> completeRanges) {
    public ArchiveCoverage {
        dataset = Objects.requireNonNull(dataset, "dataset");
        if (projectionVersion < 1) {
            throw new IllegalArgumentException("projectionVersion must be at least 1");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        completeRanges = List.copyOf(Objects.requireNonNull(completeRanges, "completeRanges"));
        completeRanges = completeRanges.stream()
                .map(range -> Objects.requireNonNull(range, "completeRanges must not contain null"))
                .sorted(Comparator.comparingLong(ArchiveRange::startInclusive))
                .toList();
        SourceKind kind = dataset.sourceKind();
        long previousEnd = -1;
        for (ArchiveRange range : completeRanges) {
            if (range.sourceKind() != kind || range.startInclusive() <= previousEnd) {
                throw new IllegalArgumentException("coverage ranges must be non-overlapping and source-compatible");
            }
            previousEnd = range.endInclusive();
        }
    }

    public boolean covers(long value) {
        return completeRanges.stream().anyMatch(r -> value >= r.startInclusive() && value <= r.endInclusive());
    }
}
