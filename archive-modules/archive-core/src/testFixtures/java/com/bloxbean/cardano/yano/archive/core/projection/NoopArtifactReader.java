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

    private int failAfter = Integer.MAX_VALUE;

    /**
     * Throw once the given number of acknowledgements have succeeded, to simulate a crash
     * partway through releasing a batch's artifacts.
     */
    public void failAcknowledgeAfter(int successes) {
        this.failAfter = successes;
    }

    @Override
    public void acknowledge(ProjectionArtifactRef ref) {
        calls++;
        if (acknowledged.size() >= failAfter) {
            throw new IllegalStateException("simulated crash during artifact acknowledgement");
        }
        // Idempotent, as the contract requires: the consumer acknowledges before the outbox
        // drops the reference, so a crash in between replays the acknowledgement.
        if (!acknowledged.contains(ref)) acknowledged.add(ref);
    }

    public List<ProjectionArtifactRef> acknowledged() {
        return List.copyOf(acknowledged);
    }

    /** Total calls, including repeats, so idempotency can be distinguished from never-called. */
    public int acknowledgeCalls() {
        return calls;
    }

    private int calls;
}
