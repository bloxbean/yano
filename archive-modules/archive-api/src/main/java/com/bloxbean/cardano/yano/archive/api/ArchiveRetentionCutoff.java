package com.bloxbean.cardano.yano.archive.api;

/** Inclusive source coordinate below which retained rows may be removed. */
public record ArchiveRetentionCutoff(SourceKind sourceKind, long beforeExclusive) {
    public ArchiveRetentionCutoff {
        if (sourceKind == null || beforeExclusive < 0) {
            throw new IllegalArgumentException("invalid archive retention cutoff");
        }
    }
}
