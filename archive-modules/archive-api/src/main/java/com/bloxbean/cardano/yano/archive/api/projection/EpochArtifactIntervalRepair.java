package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.List;
import java.util.Objects;

/** Atomic replacement of one caused pause interval after verified epoch repair. */
public record EpochArtifactIntervalRepair(
        ArchiveDatasetId dataset,
        int causedByEpoch,
        List<EpochArtifactGapInterval> survivingSegments) {
    public EpochArtifactIntervalRepair {
        Objects.requireNonNull(dataset, "dataset");
        if (causedByEpoch < 0) throw new IllegalArgumentException("causing epoch must be non-negative");
        survivingSegments = List.copyOf(Objects.requireNonNull(survivingSegments, "survivingSegments"));
        if (survivingSegments.stream().anyMatch(segment -> segment.dataset() != dataset
                || segment.causedByEpoch() != causedByEpoch)) {
            throw new IllegalArgumentException("interval repair segments do not match their cause");
        }
    }
}
