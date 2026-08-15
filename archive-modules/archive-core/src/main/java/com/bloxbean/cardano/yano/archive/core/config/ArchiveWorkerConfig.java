package com.bloxbean.cardano.yano.archive.core.config;

import java.time.Duration;
import java.util.Objects;

public record ArchiveWorkerConfig(Duration pollInterval, int maxBlocksPerBatch,
                                  int maxRowsPerBatch, long bulkPauseCoreLagBlocks,
                                  int projectionParallelism) {
    public ArchiveWorkerConfig {
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero() || maxBlocksPerBatch < 1
                || maxRowsPerBatch < 1 || bulkPauseCoreLagBlocks < 0 || projectionParallelism < 1) {
            throw new IllegalArgumentException("invalid bounded archive worker configuration");
        }
    }

    /** Compatibility constructor for deterministic single-projection callers. */
    public ArchiveWorkerConfig(Duration pollInterval, int maxBlocksPerBatch,
                               int maxRowsPerBatch, long bulkPauseCoreLagBlocks) {
        this(pollInterval, maxBlocksPerBatch, maxRowsPerBatch, bulkPauseCoreLagBlocks, 1);
    }

    public static int automaticProjectionParallelism(int availableProcessors, int enabledProjections) {
        if (availableProcessors < 1) throw new IllegalArgumentException("availableProcessors must be positive");
        if (enabledProjections < 1) throw new IllegalArgumentException("enabledProjections must be positive");
        return Math.min(enabledProjections, Math.min(4, Math.max(1, availableProcessors / 2)));
    }

    public static ArchiveWorkerConfig defaults() {
        return new ArchiveWorkerConfig(Duration.ofSeconds(1), 1_000, 250_000, 100,
                automaticProjectionParallelism(Runtime.getRuntime().availableProcessors(), 4));
    }
}
