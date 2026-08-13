package com.bloxbean.cardano.yano.archive.core.config;

import java.time.Duration;
import java.util.Objects;

public record ArchiveWorkerConfig(Duration pollInterval, int maxBlocksPerBatch,
                                  int maxRowsPerBatch, long bulkPauseCoreLagBlocks) {
    public ArchiveWorkerConfig {
        Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero() || maxBlocksPerBatch < 1
                || maxRowsPerBatch < 1 || bulkPauseCoreLagBlocks < 0) {
            throw new IllegalArgumentException("invalid bounded archive worker configuration");
        }
    }

    public static ArchiveWorkerConfig defaults() {
        return new ArchiveWorkerConfig(Duration.ofSeconds(1), 1_000, 250_000, 100);
    }
}
