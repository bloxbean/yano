package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveBackendProvider;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** ServiceLoader entry point; runtime passes only prevalidated properties. */
public final class DuckLakeArchiveBackendProvider implements ArchiveBackendProvider {
    @Override
    public String engine() {
        return "ducklake";
    }

    @Override
    public ArchiveBackend open(ArchiveIdentity expectedIdentity, Path historyDirectory,
                               Map<String, String> validatedProperties) {
        DuckLakeArchiveConfig archiveConfig = archiveConfig(historyDirectory, validatedProperties);
        Path temp = path(validatedProperties, "temp.path", historyDirectory.resolve("tmp"));
        Path extensions = path(validatedProperties, "extensions.path", historyDirectory.resolve("extensions"));
        DuckDbManagerConfig defaultsManager = DuckDbManagerConfig.defaults(temp);
        DuckDbManagerConfig manager = new DuckDbManagerConfig(
                number(validatedProperties, "duckdb.max-total-memory-bytes", defaultsManager.maxTotalMemoryBytes()),
                integer(validatedProperties, "duckdb.max-concurrent-queries", defaultsManager.maxConcurrentQueries()),
                integer(validatedProperties, "duckdb.max-concurrent-bulk-jobs", defaultsManager.maxConcurrentBulkJobs()),
                temp,
                number(validatedProperties, "duckdb.max-temp-directory-bytes", defaultsManager.maxTempDirectoryBytes()),
                new DuckDbWorkloadConfig(
                        number(validatedProperties, "duckdb.steady-memory-bytes", defaultsManager.steadyState().memoryLimitBytes()),
                        integer(validatedProperties, "duckdb.steady-threads", defaultsManager.steadyState().threads())),
                new DuckDbWorkloadConfig(
                        number(validatedProperties, "duckdb.bulk-memory-bytes", defaultsManager.bulkCatchUp().memoryLimitBytes()),
                        integer(validatedProperties, "duckdb.bulk-threads", defaultsManager.bulkCatchUp().threads())));
        return DuckLakeHistoryArchiveBackend.open(expectedIdentity, archiveConfig, manager,
                new PackagedDuckDbExtensionLoader(extensions), archiveConfig.waitPolicy());
    }

    static DuckLakeArchiveConfig archiveConfig(Path historyDirectory,
                                               Map<String, String> validatedProperties) {
        Path catalog = path(validatedProperties, "catalog.path",
                historyDirectory.resolve("ducklake-catalog.sqlite"));
        Path data = path(validatedProperties, "data.path", historyDirectory.resolve("ducklake-data"));
        DuckLakeArchiveConfig defaults = DuckLakeArchiveConfig.defaults(historyDirectory);
        return new DuckLakeArchiveConfig(catalog, data,
                defaults.acquireTimeout(), defaults.maxRetries(), defaults.retryWaitMillis(),
                number(validatedProperties, "target-file-size-bytes", defaults.targetFileSizeBytes()),
                integer(validatedProperties, "row-group-size", defaults.rowGroupSize()),
                Duration.ofHours(number(validatedProperties, "snapshot-retention-hours",
                        defaults.snapshotRetention().toHours())),
                Duration.ofHours(number(validatedProperties, "cleanup-grace-hours",
                        defaults.cleanupGrace().toHours())),
                ArchiveWaitPolicy.fromProperties(validatedProperties, defaults.waitPolicy()));
    }

    private static Path path(Map<String, String> properties, String name, Path fallback) {
        String configured = properties.get(name);
        return configured == null || configured.isBlank() ? fallback : Path.of(configured);
    }

    private static long number(Map<String, String> properties, String name, long fallback) {
        String value = properties.get(name);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value);
    }

    private static int integer(Map<String, String> properties, String name, int fallback) {
        return Math.toIntExact(number(properties, name, fallback));
    }
}
