package com.bloxbean.cardano.yano.archive.ducklake;

import java.nio.file.Path;
import java.util.Objects;

public record DuckDbManagerConfig(
        long maxTotalMemoryBytes,
        int maxConcurrentQueries,
        int maxConcurrentBulkJobs,
        Path tempDirectory,
        long maxTempDirectoryBytes,
        DuckDbWorkloadConfig steadyState,
        DuckDbWorkloadConfig bulkCatchUp) {

    private static final long MIB = 1024L * 1024;

    public DuckDbManagerConfig {
        Objects.requireNonNull(tempDirectory, "tempDirectory");
        Objects.requireNonNull(steadyState, "steadyState");
        Objects.requireNonNull(bulkCatchUp, "bulkCatchUp");
        if (maxTotalMemoryBytes < 32 * MIB || maxConcurrentQueries < 1
                || maxConcurrentBulkJobs < 1 || maxConcurrentBulkJobs >= maxConcurrentQueries
                || maxTempDirectoryBytes < 0) {
            throw new IllegalArgumentException("invalid DuckDB manager bounds");
        }
        if (Math.multiplyExact(steadyState.memoryLimitBytes(), maxConcurrentQueries) > maxTotalMemoryBytes) {
            throw new IllegalArgumentException("steady query limits exceed aggregate DuckDB memory");
        }
        long bulkAndReservedSteady = Math.addExact(
                Math.multiplyExact(bulkCatchUp.memoryLimitBytes(), maxConcurrentBulkJobs),
                steadyState.memoryLimitBytes());
        if (bulkAndReservedSteady > maxTotalMemoryBytes) {
            throw new IllegalArgumentException("bulk jobs would consume the steady query reservation");
        }
    }

    public static DuckDbManagerConfig defaults(Path tempDirectory) {
        return new DuckDbManagerConfig(256 * MIB, 2, 1, tempDirectory, 2L * 1024 * MIB,
                new DuckDbWorkloadConfig(128 * MIB, 1),
                new DuckDbWorkloadConfig(128 * MIB, 1));
    }
}
