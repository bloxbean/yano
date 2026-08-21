package com.bloxbean.cardano.yano.archive.core.projection;

/** Bounds on one sink batch and on total retained backlog (ADR-039 §9). */
public record ProjectionConsumerBounds(int maxBlocksPerBatch, long maxBytesPerBatch,
                                       long softBacklogBlocks, long hardBacklogBlocks,
                                       long softBacklogBytes, long hardBacklogBytes) {
    public ProjectionConsumerBounds {
        if (maxBlocksPerBatch < 1) throw new IllegalArgumentException("maxBlocksPerBatch must be positive");
        if (maxBytesPerBatch < 1) throw new IllegalArgumentException("maxBytesPerBatch must be positive");
        if (softBacklogBlocks < 1 || hardBacklogBlocks < softBacklogBlocks) {
            throw new IllegalArgumentException("hard block backlog bound must be at least the soft bound");
        }
        if (softBacklogBytes < 1 || hardBacklogBytes < softBacklogBytes) {
            throw new IllegalArgumentException("hard byte backlog bound must be at least the soft bound");
        }
    }

    public static ProjectionConsumerBounds defaults() {
        return new ProjectionConsumerBounds(2_000, 64L << 20,
                500_000, 2_000_000, 8L << 30, 32L << 30);
    }
}
