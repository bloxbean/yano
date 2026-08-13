package com.bloxbean.cardano.yano.archive.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable proof that one deterministic archive job was committed. */
public record ArchiveReceipt(
        UUID jobId,
        ArchiveDatasetId dataset,
        int projectionVersion,
        ArchiveRange range,
        long backendGeneration,
        Map<String, Long> rowCounts,
        String orderedDigest,
        Instant committedAt) {
    public ArchiveReceipt {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(range, "range");
        rowCounts = Map.copyOf(rowCounts);
        Objects.requireNonNull(orderedDigest, "orderedDigest");
        Objects.requireNonNull(committedAt, "committedAt");
        if (projectionVersion < 1 || backendGeneration < 0) throw new IllegalArgumentException("invalid receipt version");
    }
}
