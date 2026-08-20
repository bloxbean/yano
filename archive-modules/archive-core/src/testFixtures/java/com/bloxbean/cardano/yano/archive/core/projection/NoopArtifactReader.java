package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Artifact reader for block-only tests; records acknowledgements so leases can be asserted. */
public final class NoopArtifactReader implements ArchiveArtifactReader {

    private final List<ProjectionArtifactRef> acknowledged = new ArrayList<>();

    @Override
    public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        return new ArtifactLease() {
            private boolean open = true;
            private Instant expiry = expiresAt;

            @Override public UUID leaseId() { return id; }
            @Override public String ownerFence() { return "test"; }
            @Override public Instant expiresAt() { return expiry; }
            @Override public ArtifactLease renew(Instant newExpiry) { expiry = newExpiry; return this; }
            @Override public boolean isOpen() { return open; }
            @Override public void close() { open = false; }
        };
    }

    @Override
    public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor, int limit) {
        return new ArtifactPage(List.of(), Optional.empty());
    }

    @Override
    public void acknowledge(ProjectionArtifactRef ref) {
        acknowledged.add(ref);
    }

    public List<ProjectionArtifactRef> acknowledged() {
        return List.copyOf(acknowledged);
    }
}
