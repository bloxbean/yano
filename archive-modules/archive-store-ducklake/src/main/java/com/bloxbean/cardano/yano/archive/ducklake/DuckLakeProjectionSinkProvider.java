package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkProvider;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;

/**
 * Opens DuckLake as the ADR-039 primary projection sink.
 *
 * <p>Schema and identity are initialised directly. The read facade separately builds its
 * optional transaction locator, keeping that query accelerator off the acknowledgement path.
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
            // Initialise writer-owned schema directly. Opening the read facade here would also
            // build its transaction locator, which does not belong on the acknowledgement path.
            try (DuckDbLease lease = manager.acquire(DuckDbWorkload.BULK_CATCH_UP,
                    archiveConfig.acquireTimeout())) {
                Connection connection = lease.connection();
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
