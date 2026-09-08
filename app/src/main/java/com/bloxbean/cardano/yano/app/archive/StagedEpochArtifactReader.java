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

    /** One page of a staged artifact's rows, with the cursor that continues it. */
    public record EvidencePage(List<ArchiveRow> rows, Optional<String> nextCursor) { }

    /** Supplies rows for a staged job, and can release it once acknowledged. */
    public interface StagedEvidenceSource {
        /** One page of materialised archive rows, verified before the first row. */
        EvidencePage rows(ProjectionArtifactRef ref, Optional<String> cursor, int limit);

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
        // Fail this drain attempt before the lease is granted. Evidence that has gone cannot be
        // recomputed, so an absent file must stop archive progress rather than commit an epoch
        // with no rows. ProjectionHistoryService reports/retries it without stopping L1 sync.
        if (!source.present(ref)) {
            throw new IllegalStateException("staged evidence for " + ref.dataset() + " epoch "
                    + ref.semanticEpoch() + " (" + ref.sourceGeneration() + ") is missing or"
                    + " damaged; it cannot be reproduced once the boundary has passed");
        }
        openLeases.merge(key(ref), 1, Integer::sum);
        return new StagedLease(key(ref), expiresAt);
    }

    /**
     * One page of a staged artifact, not the whole thing.
     *
     * <p>This used to ignore {@code limit}, materialise the complete artifact and then encode it
     * into a second list. A mainnet reward epoch is over a million rows, so peak heap tracked the
     * largest epoch the chain has ever produced - and an OOM here kills the drain while it holds
     * evidence that cannot be recomputed.
     *
     * <p>The declared row count is still enforced, on the last page rather than the only one. The
     * lease carries the running total, so a truncated artifact is refused before its epoch can be
     * committed as complete, exactly as before.
     */
    @Override
    public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor,
                             int limit) {
        if (!lease.isOpen()) throw new IllegalStateException("artifact lease is closed");
        if (limit <= 0) throw new IllegalArgumentException("artifact page limit must be positive");

        EvidencePage page = sourceFor(ref).rows(ref, cursor, limit);
        long total = lease instanceof StagedLease staged ? staged.observe(page.rows().size())
                : page.rows().size();

        if (page.nextCursor().isEmpty()) {
            long expected = ref.expectedRowCount().orElse(-1);
            if (expected >= 0 && total != expected) {
                throw new IllegalStateException("staged evidence for " + ref.dataset() + " epoch "
                        + ref.semanticEpoch() + " yielded " + total + " rows but the reference"
                        + " declares " + expected);
            }
        }
        List<byte[]> encoded = new ArrayList<>(page.rows().size());
        for (ArchiveRow row : page.rows()) encoded.add(ArchiveRowCodec.encode(row));
        return new ArtifactPage(encoded, page.nextCursor());
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
        private long rowsSeen;

        /** Accumulate this page into the lease's running total. */
        long observe(int rows) {
            rowsSeen += rows;
            return rowsSeen;
        }

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
