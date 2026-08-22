package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec;
import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves epoch artifacts whose evidence is a staged file.
 *
 * <p>For REWARD, DREP_DISTRIBUTION and GOVERNANCE_PROPOSAL_STATUS there is no persisted
 * generation to reference and no small payload to inline. Rewards depend on a calculation whose
 * complete deterministic input closure is not proven; DRep distribution mixes persisted stake
 * amounts with boundary-time expiry, dormancy and active flags that later state overwrites; a
 * governance proposal's status and decision reason are an observation at a boundary, and the
 * mutable governance state that follows records the outcome rather than the observation. All
 * three are irreproducible once the boundary has passed, so the evidence has to be captured and
 * kept until the sink has durably committed it.
 *
 * <p>The lease is what stops cleanup deleting a file mid-read, and acknowledgement is what
 * permits cleanup at all — never the other way round. A file is deleted only after the sink's
 * receipt proves its rows are durable somewhere else.
 */
public final class StagedEpochArtifactReader implements ArchiveArtifactReader {

    /** Supplies rows for a staged job, and can release it once acknowledged. */
    public interface StagedEvidenceSource {
        /** Materialised archive rows for one artifact, verified before the first row. */
        List<ArchiveRow> rows(ProjectionArtifactRef ref);

        /** Release the staged evidence; called only after a durable receipt. */
        void release(ProjectionArtifactRef ref);

        /** Whether the evidence for this reference is still present and intact. */
        boolean present(ProjectionArtifactRef ref);
    }

    private final Map<ArchiveDatasetId, StagedEvidenceSource> sources;

    /** Open leases by artifact, so cleanup cannot delete a file being read. */
    private final Map<String, Integer> openLeases = new ConcurrentHashMap<>();

    public StagedEpochArtifactReader(Map<ArchiveDatasetId, StagedEvidenceSource> sources) {
        this.sources = Map.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    private StagedEvidenceSource sourceFor(ProjectionArtifactRef ref) {
        var source = sources.get(ref.dataset());
        if (source == null) {
            throw new IllegalStateException("no staged evidence source is installed for "
                    + ref.dataset() + "; the archive references an artifact this node cannot serve");
        }
        return source;
    }

    private static String key(ProjectionArtifactRef ref) {
        return ref.dataset().name() + '#' + ref.semanticEpoch() + '#' + ref.sourceGeneration();
    }

    @Override
    public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
        var source = sourceFor(ref);
        // Fail closed before the lease is granted. Evidence that has gone cannot be recomputed,
        // so an absent file must stop the drain rather than commit an epoch with no rows.
        if (!source.present(ref)) {
            throw new IllegalStateException("staged evidence for " + ref.dataset() + " epoch "
                    + ref.semanticEpoch() + " (" + ref.sourceGeneration() + ") is missing or"
                    + " damaged; it cannot be reproduced once the boundary has passed");
        }
        openLeases.merge(key(ref), 1, Integer::sum);
        return new StagedLease(key(ref), expiresAt);
    }

    @Override
    public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor,
                             int limit) {
        if (!lease.isOpen()) throw new IllegalStateException("artifact lease is closed");
        if (cursor.isPresent()) return new ArtifactPage(List.of(), Optional.empty());

        List<ArchiveRow> rows = sourceFor(ref).rows(ref);
        long expected = ref.expectedRowCount().orElse(-1);
        if (expected >= 0 && rows.size() != expected) {
            throw new IllegalStateException("staged evidence for " + ref.dataset() + " epoch "
                    + ref.semanticEpoch() + " yielded " + rows.size() + " rows but the reference"
                    + " declares " + expected);
        }
        List<byte[]> encoded = new ArrayList<>(rows.size());
        for (ArchiveRow row : rows) encoded.add(ArchiveRowCodec.encode(row));
        return new ArtifactPage(encoded, Optional.empty());
    }

    @Override
    public void acknowledge(ProjectionArtifactRef ref) {
        // Idempotent: the consumer releases artifacts before the outbox drops their reference, so
        // a crash in between replays this call.
        openLeases.remove(key(ref));
        sourceFor(ref).release(ref);
    }

    @Override
    public void reconcileAfterRestart(Collection<ProjectionArtifactRef> pending) {
        // Staged files are their own durable record, and the outbox's surviving references say
        // which are still needed. Nothing is released here: an artifact still referenced must
        // keep its evidence, and one no longer referenced was already released on acknowledgement.
        openLeases.clear();
    }

    /** Whether any read is currently in flight for this artifact. */
    public boolean isLeased(ProjectionArtifactRef ref) {
        return openLeases.containsKey(key(ref));
    }

    private final class StagedLease implements ArtifactLease {
        private final UUID id = UUID.randomUUID();
        private final String artifact;
        private Instant expiry;
        private boolean open = true;

        StagedLease(String artifact, Instant expiry) {
            this.artifact = artifact;
            this.expiry = expiry;
        }

        @Override public UUID leaseId() { return id; }
        @Override public String ownerFence() { return "projection-drain"; }
        @Override public Instant expiresAt() { return expiry; }
        @Override public ArtifactLease renew(Instant newExpiry) { expiry = newExpiry; return this; }
        @Override public boolean isOpen() { return open; }

        @Override
        public void close() {
            if (!open) return;
            open = false;
            openLeases.computeIfPresent(artifact, (k, count) -> count <= 1 ? null : count - 1);
        }
    }
}
