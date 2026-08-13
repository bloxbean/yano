package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveBackendProvider;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;

import java.nio.file.Path;
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
        Path catalog = path(validatedProperties, "catalog.path",
                historyDirectory.resolve("ducklake-catalog.sqlite"));
        Path data = path(validatedProperties, "data.path", historyDirectory.resolve("ducklake-data"));
        Path temp = path(validatedProperties, "temp.path", historyDirectory.resolve("tmp"));
        Path extensions = path(validatedProperties, "extensions.path", historyDirectory.resolve("extensions"));
        DuckLakeArchiveConfig defaults = DuckLakeArchiveConfig.defaults(historyDirectory);
        DuckLakeArchiveConfig archiveConfig = new DuckLakeArchiveConfig(catalog, data,
                defaults.acquireTimeout(), defaults.maxRetries(), defaults.retryWaitMillis(),
                defaults.targetFileSizeBytes(), defaults.rowGroupSize(),
                defaults.snapshotRetention(), defaults.cleanupGrace());
        return DuckLakeHistoryArchiveBackend.open(expectedIdentity, archiveConfig,
                DuckDbManagerConfig.defaults(temp), new PackagedDuckDbExtensionLoader(extensions));
    }

    private Path path(Map<String, String> properties, String name, Path fallback) {
        String configured = properties.get(name);
        return configured == null || configured.isBlank() ? fallback : Path.of(configured);
    }
}
