package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.Optional;
import java.util.List;
import java.util.Map;

/**
 * The one configured authoritative historical store for this node (ADR-039 §13).
 *
 * <p>Exactly one sink is active. Version one deliberately does not support two
 * simultaneous primary sinks, because cleanup would then depend on per-consumer
 * offsets and the slowest consumer would retain every envelope.
 */
public interface ProjectionSink extends AutoCloseable {

    /** Engine name recorded in archive identity, e.g. {@code ducklake} or {@code sqlite}. */
    String engine();

    /**
     * Bind this sink to the expected identity, failing closed on any mismatch of
     * network, genesis, sink engine, projection version, or required section set.
     * A sink that cannot read a required section must fail here, not at first use.
     */
    void initialize(ProjectionIdentity expected);

    /**
     * Install or verify the selected epoch-artifact contracts and enrollment lifetime.
     * Implementations must make prospective additions idempotent and refuse removals or
     * changed contracts.
     */
    default void initializeArtifacts(ProjectionArtifactIdentity identity,
                                     ProjectionArtifactEnrollments enrollments) {
        if (!identity.isEmpty()) {
            throw new UnsupportedOperationException("sink does not support epoch artifacts");
        }
    }

    /** Complete semantic-epoch ranges committed atomically with artifact rows. */
    default Map<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId,
            List<com.bloxbean.cardano.yano.archive.api.ArchiveRange>> epochArtifactCoverage() {
        return Map.of();
    }

    /** Commit a positive GAP outcome idempotently; a conflicting outcome must fail closed. */
    default void recordEpochArtifactGap(EpochArtifactGap gap) {
        throw new UnsupportedOperationException("sink does not support epoch-artifact gaps");
    }

    /** Durable point gaps currently held by the sink. */
    default List<EpochArtifactGap> epochArtifactGaps() {
        return List.of();
    }

    /** Whether this exact semantic epoch and canonical boundary point is durably COMPLETE. */
    default boolean hasCompleteEpochArtifact(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset,
            int semanticEpoch, long boundarySlot, byte[] boundaryHash) {
        return false;
    }

    /** Upsert the compact range of later boundaries missed while a dataset is paused. */
    default void recordEpochArtifactGapInterval(EpochArtifactGapInterval interval) {
        throw new UnsupportedOperationException("sink does not support epoch-artifact gap intervals");
    }

    default List<EpochArtifactGapInterval> epochArtifactGapIntervals() { return List.of(); }

    /** Atomically replace the sink's compact pause intervals with authoritative outbox state. */
    default void replaceEpochArtifactGapIntervals(List<EpochArtifactGapInterval> intervals) {
        if (!intervals.isEmpty()) {
            throw new UnsupportedOperationException("sink does not support epoch-artifact gap intervals");
        }
    }

    /** Remove non-canonical epoch outcomes after an exact-point rollback. */
    default void rollbackEpochArtifactCoverage(long slot, byte[] hash, boolean origin) { }

    /** Greatest contiguous committed block envelope, or {@link ProjectionCoordinate#NONE}. */
    ProjectionCoordinate coordinate();

    /** Durable receipt for a range starting at this block, when one was committed. */
    Optional<ProjectionReceipt> receiptFor(long firstBlock);

    /**
     * Commit every required row and the receipt in one sink transaction.
     *
     * <p>A matching existing receipt is returned without duplicate effect; a receipt
     * that describes a differently shaped job for the same range raises
     * {@link ProjectionReceiptMismatchException}.
     */
    ProjectionReceipt append(ProjectionRowBatch batch, ArchiveArtifactReader artifacts);

    /**
     * The genesis bootstrap this archive recorded, if any.
     *
     * <p>Empty on an archive that never seeded genesis. On a populated archive that is a fatal
     * state, not a recoverable one: the distribution can still be re-derived, but the blocks
     * already committed were projected against an archive missing it.
     */
    default Optional<ProjectionGenesisReceipt> genesisReceipt() {
        return Optional.empty();
    }

    /**
     * Commit the genesis rows and their receipt in ONE sink transaction.
     *
     * <p>Atomic by construction rather than by recovery: there is no window in which the rows are
     * durable but unrecorded. A matching receipt already present returns without duplicate
     * effect, which is what makes replay after a crash safe; a receipt describing a different
     * distribution raises rather than appending a second genesis.
     *
     * <p>An empty distribution still commits a receipt. "Nothing to distribute" and "never
     * bootstrapped" must not look alike.
     */
    default ProjectionGenesisReceipt commitGenesis(ProjectionGenesisBatch batch) {
        throw new UnsupportedOperationException("sink does not support genesis bootstrap");
    }

    /**
     * Run one bounded maintenance pass.
     *
     * <p>Called by the single projection coordinator when it is caught up and idle — never on
     * a separate schedule and never by an independent worker. Mandatory housekeeping (expiring
     * snapshots, deleting obsolete and orphaned files) runs first and is budgeted separately
     * from optional compaction, so compaction can never starve the cleanup that reclaims
     * space.
     *
     * <p>Implementations must be restart-safe and idempotent, must yield to ingestion, and
     * must return {@link ProjectionMaintenance.Outcome#UNSUPPORTED} rather than silently doing
     * nothing when they have no maintenance to perform.
     */
    ProjectionMaintenance.Result maintain(ProjectionMaintenance.Budget budget);

    ProjectionSinkHealth health();

    @Override
    void close();
}
