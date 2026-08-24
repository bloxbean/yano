package com.bloxbean.cardano.yano.archive.ducklake;

public record DuckDbWorkloadConfig(long memoryLimitBytes, int threads) {
    public DuckDbWorkloadConfig {
        if (memoryLimitBytes < 16L * 1024 * 1024 || threads < 1) {
            throw new IllegalArgumentException("DuckDB workload memory and threads must be positive and bounded");
        }
    }
}
