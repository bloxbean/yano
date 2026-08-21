package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.Objects;

/**
 * Header-level description of one section, sufficient for a sink to prove the section
 * it read is complete before acknowledging the envelope (ADR-039 invariant 11).
 */
public record ProjectionSectionManifest(ProjectionSectionType type, int version, int chunkCount,
                                        long rowCount, long byteCount, String digest) {
    public ProjectionSectionManifest {
        Objects.requireNonNull(type, "type");
        digest = Objects.requireNonNull(digest, "digest").trim().toLowerCase();
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        if (chunkCount < 0 || rowCount < 0 || byteCount < 0) {
            throw new IllegalArgumentException("manifest counts must not be negative");
        }
        if (digest.isEmpty()) throw new IllegalArgumentException("digest is required");
        if (chunkCount == 0 && (rowCount != 0 || byteCount != 0)) {
            throw new IllegalArgumentException("a chunkless section cannot carry rows or bytes");
        }
    }
}
