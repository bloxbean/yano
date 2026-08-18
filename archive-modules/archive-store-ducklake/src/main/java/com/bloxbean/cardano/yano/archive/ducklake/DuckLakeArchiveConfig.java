package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * @param acquireTimeout bounded wait for callers that must not block for the full
 *                       stuck threshold, such as draining readers before a catalog backup
 * @param waitPolicy     warn-versus-stuck thresholds for ordinary writer and DuckDB
 *                       capacity waiting
 */
public record DuckLakeArchiveConfig(
        Path catalogPath,
        Path dataPath,
        Duration acquireTimeout,
        int maxRetries,
        int retryWaitMillis,
        long targetFileSizeBytes,
        int rowGroupSize,
        Duration snapshotRetention,
        Duration cleanupGrace,
        ArchiveWaitPolicy waitPolicy) {

    public DuckLakeArchiveConfig {
        catalogPath = Objects.requireNonNull(catalogPath, "catalogPath").toAbsolutePath().normalize();
        dataPath = Objects.requireNonNull(dataPath, "dataPath").toAbsolutePath().normalize();
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        Objects.requireNonNull(snapshotRetention, "snapshotRetention");
        Objects.requireNonNull(cleanupGrace, "cleanupGrace");
        Objects.requireNonNull(waitPolicy, "waitPolicy");
        if (catalogPath.startsWith(dataPath) || dataPath.startsWith(catalogPath)
                || acquireTimeout.isNegative() || acquireTimeout.isZero()
                || maxRetries < 1 || retryWaitMillis < 1 || targetFileSizeBytes < 1024 * 1024
                || rowGroupSize < 1_000 || snapshotRetention.isNegative() || snapshotRetention.isZero()
                || cleanupGrace.isNegative() || cleanupGrace.isZero()) {
            throw new IllegalArgumentException("invalid DuckLake archive configuration");
        }
    }

    public DuckLakeArchiveConfig(Path catalogPath, Path dataPath, Duration acquireTimeout, int maxRetries,
                                 int retryWaitMillis, long targetFileSizeBytes, int rowGroupSize,
                                 Duration snapshotRetention, Duration cleanupGrace) {
        this(catalogPath, dataPath, acquireTimeout, maxRetries, retryWaitMillis, targetFileSizeBytes,
                rowGroupSize, snapshotRetention, cleanupGrace, ArchiveWaitPolicy.defaults());
    }

    public static DuckLakeArchiveConfig defaults(Path historyDirectory) {
        return new DuckLakeArchiveConfig(historyDirectory.resolve("ducklake-catalog.sqlite"),
                historyDirectory.resolve("ducklake-data"), Duration.ofSeconds(30), 10, 100,
                4L * 1024 * 1024, 100_000, Duration.ofHours(168), Duration.ofHours(24),
                ArchiveWaitPolicy.defaults());
    }
}
