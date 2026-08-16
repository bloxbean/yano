package com.bloxbean.cardano.yano.archive.ducklake;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record DuckLakeArchiveConfig(
        Path catalogPath,
        Path dataPath,
        Duration acquireTimeout,
        int maxRetries,
        int retryWaitMillis,
        long targetFileSizeBytes,
        int rowGroupSize,
        Duration snapshotRetention,
        Duration cleanupGrace) {

    public DuckLakeArchiveConfig {
        catalogPath = Objects.requireNonNull(catalogPath, "catalogPath").toAbsolutePath().normalize();
        dataPath = Objects.requireNonNull(dataPath, "dataPath").toAbsolutePath().normalize();
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        Objects.requireNonNull(snapshotRetention, "snapshotRetention");
        Objects.requireNonNull(cleanupGrace, "cleanupGrace");
        if (catalogPath.startsWith(dataPath) || dataPath.startsWith(catalogPath)
                || acquireTimeout.isNegative() || acquireTimeout.isZero()
                || maxRetries < 1 || retryWaitMillis < 1 || targetFileSizeBytes < 1024 * 1024
                || rowGroupSize < 1_000 || snapshotRetention.isNegative() || snapshotRetention.isZero()
                || cleanupGrace.isNegative() || cleanupGrace.isZero()) {
            throw new IllegalArgumentException("invalid DuckLake archive configuration");
        }
    }

    public static DuckLakeArchiveConfig defaults(Path historyDirectory) {
        return new DuckLakeArchiveConfig(historyDirectory.resolve("ducklake-catalog.sqlite"),
                historyDirectory.resolve("ducklake-data"), Duration.ofSeconds(30), 10, 100,
                4L * 1024 * 1024, 100_000, Duration.ofHours(168), Duration.ofHours(24));
    }
}
