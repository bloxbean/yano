package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Artifact reader for the block-only slice.
 *
 * <p>The first vertical slice produces no epoch artifacts, so any request here means an
 * envelope referenced one that cannot exist. Failing loudly is correct: silently returning an
 * empty stream would let the sink acknowledge an artifact it never read, which is exactly the
 * silent-loss failure ADR-039 exists to prevent. Replaced in Phase 5.
 */
final class NoArtifactReader implements ArchiveArtifactReader {

    @Override
    public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
        throw new UnsupportedOperationException("epoch artifacts are not produced by this slice: " + ref);
    }

    @Override
    public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor, int limit) {
        throw new UnsupportedOperationException("epoch artifacts are not produced by this slice: " + ref);
    }

    @Override
    public void acknowledge(ProjectionArtifactRef ref) {
        throw new UnsupportedOperationException("epoch artifacts are not produced by this slice: " + ref);
    }
}
