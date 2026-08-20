package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.Optional;

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
