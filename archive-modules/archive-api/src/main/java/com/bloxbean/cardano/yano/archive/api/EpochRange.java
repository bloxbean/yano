package com.bloxbean.cardano.yano.archive.api;

public record EpochRange(long startInclusive, long endInclusive) implements ArchiveRange {
    public EpochRange {
        if (startInclusive < 0 || endInclusive < startInclusive) {
            throw new IllegalArgumentException("invalid epoch range");
        }
    }

    @Override
    public SourceKind sourceKind() {
        return SourceKind.EPOCH;
    }
}
