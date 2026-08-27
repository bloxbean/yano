package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.archive.SnapshotRetentionClamp;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.core.projection.EpochStakeArtifactRows;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads epoch artifacts straight from the generation the boundary persisted.
 *
 * <p>There is no copy to read from: an epoch-stake artifact is a reference to the delegation
 * snapshot in the live store, so this pages that snapshot under a lease. The lease is what stops
 * retention deleting the generation mid-read - the pruning clamp holds the floor while any lease
 * is open, and only releases once the sink has durably committed the rows.
 */
public final class EpochSnapshotArtifactReader implements ArchiveArtifactReader {

    private record Boundary(byte[] blockHash, long blockTimeSeconds) {}

    private final DefaultAccountStateStore accountState;
    private final SnapshotRetentionClamp clamp;
    private final int pageSize;
    private final long networkMagic;
    private final ArtifactBoundaryFacts boundaryFacts;

    /** Resolved once per artifact, not once per row: a snapshot is millions of rows. */
    private final Map<Integer, Boundary> boundaries = new ConcurrentHashMap<>();

    /** Open leases by artifact, so the clamp can hold the oldest epoch still being read. */
    private final Map<Integer, Integer> openLeasesByEpoch = new ConcurrentHashMap<>();

    /**
     * Epochs still referenced by the outbox.
     *
     * <p>Separate from the lease map because these outlive any read: an artifact staged before a
     * restart has no lease but its source must still be protected. Held as a set rather than a
     * single floor so acknowledging one epoch cannot release protection for another that is
     * still pending.
     */
    private final java.util.Set<Integer> pendingEpochs = ConcurrentHashMap.newKeySet();

    public EpochSnapshotArtifactReader(DefaultAccountStateStore accountState,
                                       SnapshotRetentionClamp clamp, int pageSize,
                                       long networkMagic, ArtifactBoundaryFacts boundaryFacts) {
        // Not required up front: acknowledgement and the dataset guard never touch the store, and
        // the lease bookkeeping they drive is what retention safety depends on. Reads check it.
        this.accountState = accountState;
        this.clamp = clamp == null ? SnapshotRetentionClamp.NONE : clamp;
        this.pageSize = pageSize > 0 ? pageSize : 10_000;
        this.networkMagic = networkMagic;
        this.boundaryFacts = boundaryFacts;
    }

    @Override
    public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
        require(ref);
        // Resolve the boundary before the lease is handed out. A missing canonical reference must
        // fail here: writing the rows with a null boundary_block_hash would produce an archive that
        // differs from the replay path while looking complete.
        boundaries.computeIfAbsent(ref.semanticEpoch(), epoch -> {
            var facts = Objects.requireNonNull(boundaryFacts, "boundary facts are required to read artifacts");
            byte[] hash = facts.blockHash(ref.producingBlockNumber()).orElseThrow(() ->
                    new IllegalStateException("no canonical block reference at boundary block "
                            + ref.producingBlockNumber() + " for epoch-stake artifact of epoch " + epoch));
            return new Boundary(hash, facts.blockTimeSeconds(ref.producingSlot()));
        });

        // Fail closed on an absent generation. Returning an empty stream would be worse than an
        // error: an epoch legitimately can have no delegators, so an empty read is indistinguishable
        // from a pruned snapshot, and the archive would record "no stake this epoch" forever.
        var probe = store().readEpochDelegSnapshotPage(ref.semanticEpoch(), null, 1);
        if (probe.rows().isEmpty() && !probe.hasMore()
                && ref.expectedRowCount().orElse(0) > 0) {
            throw new IllegalStateException("epoch-stake generation " + ref.sourceGeneration()
                    + " is no longer present, but the artifact expects "
                    + ref.expectedRowCount().orElse(0) + " rows; the snapshot was pruned while"
                    + " still referenced");
        }
        openLeasesByEpoch.merge(ref.semanticEpoch(), 1, Integer::sum);
        clampToOldestOpenLease();
        return new SnapshotLease(ref.semanticEpoch(), expiresAt);
    }

    @Override
    public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor,
                            int limit) {
        require(ref);
        if (!lease.isOpen()) throw new IllegalStateException("artifact lease is closed");

        byte[] after = cursor.map(c -> HexFormat.of().parseHex(c)).orElse(null);
        var page = store().readEpochDelegSnapshotPage(ref.semanticEpoch(), after,
                limit > 0 ? Math.min(limit, pageSize) : pageSize);

        Boundary boundary = Objects.requireNonNull(boundaries.get(ref.semanticEpoch()),
                "artifact was read without an acquired lease");
        List<byte[]> rows = new ArrayList<>(page.rows().size());
        for (var row : page.rows()) {
            // Materialise the final archive row here rather than at the sink. The sink has no
            // access to the boundary hash, the slot clock or the network magic, and must not
            // acquire any: it reaches artifacts only through this interface.
            rows.add(ArchiveRowCodec.encode(EpochStakeArtifactRows.row(
                    ref, networkMagic, row.credentialType(), row.credentialHash(), row.poolHash(),
                    row.amount(), boundary.blockHash(), boundary.blockTimeSeconds(),
                    EpochStakeArtifactRows.jobId(ref))));
        }
        return new ArtifactPage(rows, page.hasMore()
                ? Optional.of(HexFormat.of().formatHex(page.nextKey()))
                : Optional.empty());
    }

    @Override
    public void acknowledge(ProjectionArtifactRef ref) {
        // Idempotent by contract: the consumer releases artifacts before the outbox drops their
        // reference, so a crash in between replays this call.
        require(ref);
        openLeasesByEpoch.remove(ref.semanticEpoch());
        boundaries.remove(ref.semanticEpoch());
        pendingEpochs.remove(ref.semanticEpoch());
        clampToOldestOpenLease();
    }

    @Override
    public void reconcileAfterRestart(java.util.Collection<ProjectionArtifactRef> pending) {
        // Re-derive the floor from what the outbox durably still holds. An empty set is
        // meaningful: it says every artifact was acknowledged, so protection must be released
        // rather than left pinned by a stale value.
        pendingEpochs.clear();
        pending.stream()
                .filter(ref -> ref.dataset() == ArchiveDatasetId.EPOCH_STAKE)
                .map(ProjectionArtifactRef::semanticEpoch)
                .forEach(pendingEpochs::add);
        clampToOldestOpenLease();
    }

    /** Hold the floor at the oldest epoch still referenced, or release it when none remain. */
    private void clampToOldestOpenLease() {
        int leased = openLeasesByEpoch.keySet().stream()
                .mapToInt(Integer::intValue).min().orElse(-1);
        int pending = pendingEpochs.stream().mapToInt(Integer::intValue).min().orElse(-1);
        int floor = leased < 0 ? pending : pending < 0 ? leased : Math.min(leased, pending);
        clamp.protectSnapshotsFrom(floor);
    }

    private DefaultAccountStateStore store() {
        return Objects.requireNonNull(accountState, "account state store is required to read artifacts");
    }

    private static void require(ProjectionArtifactRef ref) {
        if (ref.dataset() != ArchiveDatasetId.EPOCH_STAKE) {
            throw new IllegalArgumentException("this reader serves EPOCH_STAKE, not " + ref.dataset());
        }
    }

    private final class SnapshotLease implements ArtifactLease {
        private final UUID id = UUID.randomUUID();
        private final int epoch;
        private Instant expiry;
        private boolean open = true;

        SnapshotLease(int epoch, Instant expiry) {
            this.epoch = epoch;
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
            openLeasesByEpoch.computeIfPresent(epoch, (e, count) -> count <= 1 ? null : count - 1);
            clampToOldestOpenLease();
        }
    }
}
