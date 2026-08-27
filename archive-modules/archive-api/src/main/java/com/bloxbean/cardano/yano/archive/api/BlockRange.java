package com.bloxbean.cardano.yano.archive.api;

public record BlockRange(long startInclusive, long endInclusive) implements ArchiveRange {
    public BlockRange {
        if (startInclusive < 0 || endInclusive < startInclusive) {
            throw new IllegalArgumentException("invalid block range");
        }
    }

    @Override
    public SourceKind sourceKind() {
        return SourceKind.BLOCK;
    }
}
