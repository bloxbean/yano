package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkProvider;

import java.nio.file.Path;
import java.util.Map;

/**
 * Opens DuckLake as the ADR-039 primary projection sink.
 *
 * <p>Schema and identity are initialised directly, without instantiating the legacy
 * {@code DuckLakeHistoryArchiveBackend}. That backend also builds the replay-worker
 * transaction locator, whose startup rebuild was measured at ~61 seconds on a preprod
 * archive — cost the projection path has no use for, since ADR-039 §15 keeps point lookup
 * off the acknowledgement path entirely.
 */
public final class DuckLakeProjectionSinkProvider implements ProjectionSinkProvider {

    @Override
    public String engine() {
        return "ducklake";
    }

    @Override
    public ProjectionSink openProjectionSink(ArchiveIdentity expectedIdentity, Path historyDirectory,
                                             Map<String, String> validatedProperties) {
        DuckLakeArchiveConfig archiveConfig =
                DuckLakeArchiveBackendProvider.archiveConfig(historyDirectory, validatedProperties);
        Path temp = pathOr(validatedProperties, "temp.path", historyDirectory.resolve("tmp"));
        Path extensions = pathOr(validatedProperties, "extensions.path",
                historyDirectory.resolve("extensions"));

        DuckDbManager manager = new DuckDbManager(DuckDbManagerConfig.defaults(temp),
                new PackagedDuckDbExtensionLoader(extensions));
        try {
            // Initialise schema and identity directly. Opening the legacy
            // DuckLakeHistoryArchiveBackend to do this also constructs the replay-worker
            // transaction locator, which performs a full rebuild on startup — measured at
            // ~61 seconds on a preprod archive. The projection path does not use the locator
            // at all (ADR-039 keeps point lookup off the acknowledgement path), so paying
            // that cost on every restart was pure waste.
            try (DuckDbLease lease = manager.acquire(DuckDbWorkload.BULK_CATCH_UP,
                    archiveConfig.acquireTimeout())) {
                java.sql.Connection connection = lease.connection();
                DuckLakeSql.attach(connection, archiveConfig, null, false);
                try {
                    new DuckLakeInitializer(archiveConfig).initializeProjection(connection, expectedIdentity);
                    DuckLakeProjectionSchema.initialize(connection);
                } finally {
                    DuckLakeSql.detach(connection);
                }
            }
        } catch (Exception e) {
            try {
                manager.close();
            } catch (Exception closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("failed to initialise the DuckLake projection schema", e);
        }
        return new DuckLakeProjectionSink(manager, archiveConfig);
    }

    private static Path pathOr(Map<String, String> properties, String name, Path fallback) {
        String configured = properties.get(name);
        return configured == null || configured.isBlank() ? fallback : Path.of(configured);
    }
}
