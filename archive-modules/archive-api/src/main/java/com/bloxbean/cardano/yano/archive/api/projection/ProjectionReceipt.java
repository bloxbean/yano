package com.bloxbean.cardano.yano.archive.api.projection;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Durable proof that one deterministic projection batch was committed by the sink,
 * written in the same sink transaction as its rows (ADR-039 §10).
 *
 * <p>{@link #matches} is the whole idempotency contract: a retry that agrees on
 * identity, range, counts and digest is accepted as already-done, and a retry that
 * disagrees on any of them is rejected rather than reconciled. Cleanup is scoped to a
 * verified receipt and never infers success from the sink's current maximum block.
 */
public record ProjectionReceipt(String identityFingerprint, long firstBlock, long lastBlock,
                                String firstEnvelopeId, String lastEnvelopeId,
                                long blockCount, Map<String, Long> rowCounts,
                                String orderedDigest, Instant committedAt) {
    public ProjectionReceipt {
        identityFingerprint = Objects.requireNonNull(identityFingerprint, "identityFingerprint").trim();
        firstEnvelopeId = Objects.requireNonNull(firstEnvelopeId, "firstEnvelopeId").trim().toLowerCase();
        lastEnvelopeId = Objects.requireNonNull(lastEnvelopeId, "lastEnvelopeId").trim().toLowerCase();
        orderedDigest = Objects.requireNonNull(orderedDigest, "orderedDigest").trim().toLowerCase();
        rowCounts = Map.copyOf(Objects.requireNonNull(rowCounts, "rowCounts"));
        Objects.requireNonNull(committedAt, "committedAt");
        if (firstBlock < 0 || lastBlock < firstBlock) throw new IllegalArgumentException("invalid receipt range");
        if (blockCount != lastBlock - firstBlock + 1) {
            throw new IllegalArgumentException("receipt blockCount does not match its range");
        }
        if (identityFingerprint.isEmpty() || orderedDigest.isEmpty()
                || firstEnvelopeId.isEmpty() || lastEnvelopeId.isEmpty()) {
            throw new IllegalArgumentException("receipt identity fields are required");
        }
        if (rowCounts.values().stream().anyMatch(v -> v == null || v < 0)) {
            throw new IllegalArgumentException("receipt row counts must not be negative");
        }
    }

    public static ProjectionReceipt of(ProjectionJob job, Map<String, Long> rowCounts, Instant committedAt) {
        return new ProjectionReceipt(job.identity().fingerprint(), job.firstBlock(), job.lastBlock(),
                job.firstEnvelopeId(), job.lastEnvelopeId(), job.blockCount(), rowCounts,
                job.orderedDigest(), committedAt);
    }

    /** True only when a retry is the same job; deliberately ignores {@code committedAt}. */
    public boolean matches(ProjectionJob job) {
        return identityFingerprint.equals(job.identity().fingerprint())
                && firstBlock == job.firstBlock()
                && lastBlock == job.lastBlock()
                && blockCount == job.blockCount()
                && firstEnvelopeId.equals(job.firstEnvelopeId())
                && lastEnvelopeId.equals(job.lastEnvelopeId())
                && orderedDigest.equals(job.orderedDigest());
    }
}
