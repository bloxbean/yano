package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Dispatches artifact reads to the reader that owns each dataset.
 *
 * <p>Each dataset's source has its own retention and recovery story - a protected generation for
 * epoch stake, inline evidence for the ada pot - so one reader cannot serve both without
 * conflating them. Routing keeps each contract in one place, and an unrouted dataset fails loudly
 * rather than being served by whichever reader happened to be installed.
 */
public final class RoutingArtifactReader implements ArchiveArtifactReader {

    private final Map<ArchiveDatasetId, ArchiveArtifactReader> readers;

    public RoutingArtifactReader(Map<ArchiveDatasetId, ArchiveArtifactReader> readers) {
        this.readers = Map.copyOf(Objects.requireNonNull(readers, "readers"));
    }

    private ArchiveArtifactReader readerFor(ProjectionArtifactRef ref) {
        var reader = readers.get(ref.dataset());
        if (reader == null) {
            throw new IllegalStateException("no artifact reader is installed for " + ref.dataset()
                    + "; the archive references an artifact this node cannot serve");
        }
        return reader;
    }

    @Override
    public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
        return readerFor(ref).acquire(ref, expiresAt);
    }

    @Override
    public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor,
                             int limit) {
        return readerFor(ref).read(ref, lease, cursor, limit);
    }

    @Override
    public void acknowledge(ProjectionArtifactRef ref) {
        readerFor(ref).acknowledge(ref);
    }

    @Override
    public void reconcileAfterRestart(Collection<ProjectionArtifactRef> pending) {
        // Every reader sees the whole pending set, including an empty one: releasing protection
        // is as much a part of reconciliation as re-establishing it, and a reader that was not
        // called could keep a source pinned for an artifact that no longer exists.
        readers.values().forEach(reader -> reader.reconcileAfterRestart(pending));
    }
}
