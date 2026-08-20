package com.bloxbean.cardano.yano.archive.api.projection;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded, leased access to a large immutable epoch artifact.
 *
 * <p>The sink reaches artifacts only through this interface: DuckLake and SQLite code
 * never receive raw column-family handles and never depend on physical key encoding
 * (ADR-039 §5).
 *
 * <p>A lease is a durable pruning contract, not a timer. It carries a fenced owner
 * identity so that abandonment cleanup can prove an owner is stale before releasing
 * protection; a wall-clock deadline alone is a liveness signal and never authority to
 * delete a source a reader may still be using (ADR-039 §7).
 */
public interface ArchiveArtifactReader {

    /** Fenced, renewable protection over one artifact's source bytes. */
    interface ArtifactLease extends AutoCloseable {
        UUID leaseId();

        /** Owner fence; cleanup must prove this owner is gone, not merely late. */
        String ownerFence();

        Instant expiresAt();

        ArtifactLease renew(Instant newExpiry);

        boolean isOpen();

        @Override
        void close();
    }

    /** One bounded page of artifact rows plus the cursor that continues it. */
    record ArtifactPage(List<byte[]> rows, Optional<String> nextCursor) {
        public ArtifactPage {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            Objects.requireNonNull(nextCursor, "nextCursor");
        }

        public boolean hasMore() {
            return nextCursor.isPresent();
        }
    }

    /**
     * Acquire protection before any read. Throws when the artifact is absent or its
     * source cannot be verified, so a missing artifact fails closed rather than
     * producing an empty stream that would look like a legitimately empty epoch.
     */
    ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt);

    /** Read one bounded page. The lease must be open; an expired lease is rejected. */
    ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor, int limit);

    /**
     * Report that the primary sink has durably committed this artifact. Only after
     * this may the source become eligible for deletion, and never while a live
     * reader lease exists.
     */
    void acknowledge(ProjectionArtifactRef ref);
}
