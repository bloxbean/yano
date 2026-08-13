package com.bloxbean.cardano.yano.archive.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable proof that one deterministic archive job was committed. */
public record ArchiveReceipt(
        UUID jobId,
        ArchiveNetworkIdentity networkIdentity,
        ArchiveDatasetId dataset,
        int projectionVersion,
        ArchiveRange range,
        ArchiveRangeAnchor anchors,
        long backendGeneration,
        Map<String, Long> rowCounts,
        String orderedDigest,
        Instant committedAt) {
    public ArchiveReceipt {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(anchors, "anchors");
        rowCounts = Map.copyOf(Objects.requireNonNull(rowCounts, "rowCounts"));
        orderedDigest = Objects.requireNonNull(orderedDigest, "orderedDigest").trim();
        Objects.requireNonNull(committedAt, "committedAt");
        if (projectionVersion < 1 || backendGeneration < 0) throw new IllegalArgumentException("invalid receipt version");
        if (dataset.sourceKind() != range.sourceKind()) throw new IllegalArgumentException("dataset/range source mismatch");
        if (orderedDigest.isEmpty() || rowCounts.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalArgumentException("invalid receipt digest or row counts");
        }
    }
}
