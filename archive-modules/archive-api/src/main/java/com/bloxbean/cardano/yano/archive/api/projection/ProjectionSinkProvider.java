package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;

import java.nio.file.Path;
import java.util.Map;

/**
 * ServiceLoader boundary for selecting exactly one primary projection sink.
 *
 * <p>Mirrors {@code ArchiveBackendProvider} deliberately: the host names an engine and
 * receives a {@link ProjectionSink}, never a backend-specific type. That is what keeps
 * DuckLake and standalone SQLite out of the application module and preserves ADR-039's
 * dependency direction toward {@code archive-api}.
 */
public interface ProjectionSinkProvider {

    /** Engine name, matching the configured {@code history.projection.sink}. */
    String engine();

    /**
     * Open the sink, creating any physical schema it requires.
     *
     * <p>The returned sink is not yet bound to an identity; the caller invokes
     * {@link ProjectionSink#initialize} so identity mismatches fail closed at startup.
     */
    ProjectionSink openProjectionSink(ArchiveIdentity expectedIdentity, Path historyDirectory,
                                      Map<String, String> validatedProperties);
}
