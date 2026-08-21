package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceiptMismatchException;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Ordered, finality-gated consumption of the projection outbox (ADR-039 §9, §10).
 *
 * <p>There is no {@code CATCHING_UP -> LIVE} lifecycle here. A small backlog simply
 * means a near-live archive and a large backlog means the archive is catching up; the
 * consumer's behaviour is identical in both cases, which is what removes the separate
 * per-dataset catch-up/live state machines the old workers needed.
 *
 * <p>The commit order is deliberate and is what makes at-least-once delivery produce
 * exactly-once effect:
 *
 * <pre>
 * read contiguous eligible envelopes
 *   -&gt; sink writes rows + durable receipt atomically
 *   -&gt; acknowledge the outbox range
 *   -&gt; delete acknowledged chunks
 * </pre>
 *
 * A crash between the sink commit and acknowledgement replays the same deterministic
 * batch; the existing receipt is found, matched, and the range is acknowledged without
 * writing anything twice.
 */
public final class ProjectionOutboxConsumer {

    private final ProjectionOutboxStore store;
    private final ProjectionSink sink;
    private final ProjectionIdentity identity;
    private final ProjectionFinalityGate finalityGate;
    private final ProjectionConsumerBounds bounds;
    private final ArchiveArtifactReader artifacts;
    private final LongSupplier tipBlockNumber;
    private final LongSupplier commonRollbackFloorSlot;
    private final ProjectionBatchPolicy batchPolicy;
    private final ProjectionBatchAccumulator accumulator;
    private volatile ProjectionBatchDecision lastDecision;
    private final java.util.concurrent.atomic.AtomicBoolean discardPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    public ProjectionOutboxConsumer(ProjectionOutboxStore store, ProjectionSink sink, ProjectionIdentity identity,
                                    ProjectionFinalityGate finalityGate, ProjectionConsumerBounds bounds,
                                    ArchiveArtifactReader artifacts, LongSupplier tipBlockNumber,
                                    LongSupplier commonRollbackFloorSlot) {
        this.store = Objects.requireNonNull(store, "store");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.finalityGate = Objects.requireNonNull(finalityGate, "finalityGate");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.tipBlockNumber = Objects.requireNonNull(tipBlockNumber, "tipBlockNumber");
        this.commonRollbackFloorSlot = Objects.requireNonNull(commonRollbackFloorSlot, "commonRollbackFloorSlot");
        this.batchPolicy = ProjectionBatchPolicy.defaults();
        this.accumulator = new ProjectionBatchAccumulator(batchPolicy);
    }

    public ProjectionOutboxConsumer(ProjectionOutboxStore store, ProjectionSink sink, ProjectionIdentity identity,
                                    ProjectionFinalityGate finalityGate, ProjectionConsumerBounds bounds,
                                    ArchiveArtifactReader artifacts, LongSupplier tipBlockNumber,
                                    LongSupplier commonRollbackFloorSlot, ProjectionBatchPolicy batchPolicy) {
        this.store = Objects.requireNonNull(store, "store");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.finalityGate = Objects.requireNonNull(finalityGate, "finalityGate");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.tipBlockNumber = Objects.requireNonNull(tipBlockNumber, "tipBlockNumber");
        this.commonRollbackFloorSlot = Objects.requireNonNull(commonRollbackFloorSlot, "commonRollbackFloorSlot");
        this.batchPolicy = Objects.requireNonNull(batchPolicy, "batchPolicy");
        this.accumulator = new ProjectionBatchAccumulator(batchPolicy);
    }

    /** Why the most recent batch was committed; null until one has been. */
    public ProjectionBatchDecision lastBatchDecision() {
        return lastDecision;
    }

    /**
     * Run one bounded maintenance pass on the sink.
     *
     * <p>Invoked by the same coordinator that drains, never by a separate worker, and only
     * when it has nothing eligible to drain. Compaction is offered only when the producer is
     * at tip and the outbox holds no eligible backlog; during bootstrap the budget carries
     * housekeeping alone, so cleanup still reclaims space without compaction competing with
     * the sink for the bulk pool.
     */
    public MaintenancePass maintain(java.time.Duration housekeeping,
                                    java.time.Duration compaction, long maxBytesToRewrite) {
        long from = store.acknowledgedThrough() + 1;
        long eligible = Math.min(store.completeThrough(identity.requiredSections()),
                finalityGate.eligibleThrough(tipBlockNumber.getAsLong()));
        boolean idle = eligible < from && accumulator.isEmpty();
        boolean allowCompaction = nearTip() && idle;
        var budget = allowCompaction
                ? ProjectionMaintenance.Budget.full(housekeeping, compaction, maxBytesToRewrite)
                : ProjectionMaintenance.Budget.housekeepingOnly(housekeeping);
        return new MaintenancePass(sink.maintain(budget), allowCompaction);
    }

    /**
     * What a maintenance pass was actually permitted to do, alongside what it did.
     *
     * <p>The scheduler asks for compaction on an interval, but the consumer withholds it when
     * the sink is not idle. Without this the caller would record a compaction that never ran
     * and defer the next one by a full interval.
     *
     * @param compactionOffered whether the budget actually carried compaction
     */
    public record MaintenancePass(ProjectionMaintenance.Result result, boolean compactionOffered) { }

    /** Run one bounded pass. Safe to call repeatedly; never partially acknowledges a batch. */
    public ProjectionConsumerResult drainOnce() {
        return drainOnce(java.time.Instant.now());
    }

    /**
     * Run one bounded pass against an explicit clock.
     *
     * <p>Structure matters here: a pass with <em>no</em> new envelopes must still evaluate the
     * pending batch, because "arrivals stopped" is precisely the situation the freshness
     * deadline exists for. Returning early on an empty read would make the deadline
     * unreachable.
     */
    ProjectionConsumerResult drainOnce(java.time.Instant now) {
        // A rollback removed envelopes from the outbox. Anything buffered may describe a
        // discarded fork, so it is dropped and re-read from durable state on the next pass.
        if (discardPending.compareAndSet(true, false)) {
            accumulator.reset();
            return ProjectionConsumerResult.idle();
        }

        long acknowledged = store.acknowledgedThrough();
        long complete = store.completeThrough(identity.requiredSections());
        long eligible = Math.min(complete, finalityGate.eligibleThrough(tipBlockNumber.getAsLong()));

        // The batching regime is derived from the drain backlog, not from a separate sync
        // signal. A sink that falls far behind while the node sits at tip still gets the
        // bootstrap regime, and the regime returns to near-tip on its own once it catches up.
        boolean nearTip = eligible - acknowledged < batchPolicy.minBlocks(false);


        long from = accumulator.isEmpty() ? acknowledged + 1 : accumulator.lastBlock() + 1;
        if (eligible >= from) {
            List<ProjectionEnvelope> arrivals = store.readRange(from, eligible, identity.requiredSections(),
                    bounds.maxBlocksPerBatch(), bounds.maxBytesPerBatch());
            for (ProjectionEnvelope envelope : arrivals) {
                ProjectionBatchAccumulator.Offer offer = accumulator.offer(envelope, now);
                if (offer == ProjectionBatchAccumulator.Offer.REJECTED_FULL) break;
                if (offer == ProjectionBatchAccumulator.Offer.REJECTED_UNSAFE) {
                    return ProjectionConsumerResult.paused("envelope at block "
                            + envelope.header().blockNumber() + " exceeds the absolute batch safety guard ("
                            + ProjectionBatchAccumulator.bytesOf(envelope) + " bytes, "
                            + ProjectionBatchAccumulator.rowsOf(envelope) + " rows)");
                }
            }
        }

        if (accumulator.isEmpty()) return ProjectionConsumerResult.idle();

        boolean moreAvailable = eligible > accumulator.lastBlock();
        ProjectionBatchDecision decision = accumulator.decide(nearTip, moreAvailable, now);
        if (!decision.flush()) {
            return ProjectionConsumerResult.accumulating(
                    accumulator.firstBlock(), accumulator.lastBlock(), moreAvailable);
        }

        // Re-check immediately before committing: a rollback that landed while this pass was
        // assembling must not be written to the sink.
        if (discardPending.compareAndSet(true, false)) {
            accumulator.reset();
            return ProjectionConsumerResult.idle();
        }

        ProjectionBatch batch = new ProjectionBatch(identity, accumulator.envelopes());
        verifyContiguity(batch, acknowledged);

        // Retention health is checked against what this batch actually requires, before
        // any sink work. A violation pauses; it never narrows eligibility or skips work.
        long oldestRequired = oldestRequiredSlot(batch);
        ProjectionRetentionHealth health =
                ProjectionRetentionHealth.evaluate(commonRollbackFloorSlot.getAsLong(), oldestRequired);
        if (!health.allowsProgress()) {
            return ProjectionConsumerResult.paused(health.detail().orElse("retention health violated"));
        }

        Optional<ProjectionReceipt> existing = sink.receiptFor(batch.firstBlock());
        boolean replayed = false;
        if (existing.isPresent()) {
            // Receipt identity is checked against the encoded batch, so an already-committed
            // range is recognised without decoding a single section. That keeps crash
            // recovery cheap, and it keeps a replay from re-running row derivation that the
            // committed batch already proved succeeds.
            if (!existing.get().matches(batch)) {
                throw new ProjectionReceiptMismatchException("a durable receipt already covers block "
                        + batch.firstBlock() + " but describes a different job; refusing to reconcile"
                        + " (stored range " + existing.get().firstBlock() + ".." + existing.get().lastBlock()
                        + ", offered range " + batch.firstBlock() + ".." + batch.lastBlock() + ")");
            }
            replayed = true;
        } else {
            // Materialise once, in shared code, so every backend sees the same rows.
            ProjectionReceipt receipt = sink.append(ProjectionRowBuilder.materialise(batch), artifacts);
            if (!receipt.matches(batch)) {
                throw new ProjectionReceiptMismatchException(
                        "sink returned a receipt that does not describe the committed batch at block "
                                + batch.firstBlock());
            }
        }

        // Order matters here, and the obvious order is wrong.
        //
        // acknowledgeThrough() deletes the outbox's artifact references along with the range.
        // Acknowledging the range first would therefore destroy the only record of which
        // artifacts still need releasing: a crash in the gap leaves their sources pinned
        // forever, with nothing left to reconcile from. Startup cannot repair it either,
        // because the reference it would repair from is exactly what was deleted.
        //
        // So artifact acknowledgement completes first, against a verified durable receipt.
        // Acknowledging an artifact twice is harmless - the reader's contract is idempotent -
        // whereas losing the reference is not, so a crash between the two steps costs a repeat
        // rather than a leak.
        for (var artifact : batch.artifacts()) {
            artifacts.acknowledge(artifact);
        }
        // Only now is removal authorised, and only for exactly this verified range.
        store.acknowledgeThrough(batch.lastBlock());
        accumulator.recordFlush(decision);
        lastDecision = decision;
        accumulator.reset();

        return new ProjectionConsumerResult(
                replayed ? ProjectionConsumerResult.Outcome.REPLAYED : ProjectionConsumerResult.Outcome.COMMITTED,
                batch.firstBlock(), batch.lastBlock(), Optional.empty(),
                eligible > batch.lastBlock());
    }

    /**
     * A buffered batch must still start exactly where the acknowledged range ends and must
     * cover every block in between. A duplicate would be caught downstream by the receipt
     * check; a <em>gap</em> would not, so it is made loud here.
     */
    private static void verifyContiguity(ProjectionBatch batch, long acknowledged) {
        if (batch.firstBlock() != acknowledged + 1) {
            throw new IllegalStateException("pending projection batch starts at block " + batch.firstBlock()
                    + " but the acknowledged range ends at " + acknowledged
                    + "; refusing to commit a batch that would leave a coverage gap");
        }
        long expected = batch.firstBlock();
        for (ProjectionEnvelope envelope : batch.envelopes()) {
            if (envelope.header().blockNumber() != expected) {
                throw new IllegalStateException("pending projection batch is not contiguous: expected block "
                        + expected + " but found " + envelope.header().blockNumber());
            }
            expected++;
        }
    }

    /**
     * Discard whatever is buffered, because the outbox beneath it changed.
     *
     * <p>Called from the rollback path, which runs on the event-bus thread. It deliberately
     * takes no lock the drain thread could be holding across a sink commit: the flag is
     * observed by the drain thread at its next safe point.
     */
    public void discardPendingBatch() {
        discardPending.set(true);
    }

    /**
     * Whether the sink is close enough to the producer to use the near-tip regime.
     *
     * <p>Derived from the eligible drain backlog rather than a separate sync signal, so a
     * sink that falls behind at tip is treated as bootstrapping until it catches up.
     */
    public boolean nearTip() {
        return Math.min(store.completeThrough(identity.requiredSections()),
                finalityGate.eligibleThrough(tipBlockNumber.getAsLong()))
                - store.acknowledgedThrough() < batchPolicy.minBlocks(false);
    }

    /**
     * Whether any drain work is in flight — either eligible envelopes waiting or a batch
     * already buffered.
     *
     * <p>This is deliberately the exact negation of the idle test {@link #maintain} uses to
     * decide whether compaction may be offered. If the scheduler and the consumer disagreed,
     * the scheduler would keep reporting compaction due while the consumer kept withholding
     * it, and — because nothing would ever record a compaction as having run — a maintenance
     * pass would be attempted on every drain tick, acquiring a bulk lease each time.
     */
    public boolean hasDrainBacklog() {
        if (!accumulator.isEmpty()) return true;
        return Math.min(store.completeThrough(identity.requiredSections()),
                finalityGate.eligibleThrough(tipBlockNumber.getAsLong())) > store.acknowledgedThrough();
    }

    /** Blocks currently buffered but not yet committed; zero when nothing is pending. */
    public int pendingBatchBlocks() {
        return accumulator.size();
    }

    /**
     * How long the oldest finality-eligible envelope has waited without reaching the sink,
     * in seconds; -1 when nothing is buffered.
     *
     * <p>This is the staleness the historical API must cover from live storage: it bounds the
     * window in which a block is final and durable in the outbox but not yet queryable in the
     * primary sink.
     */
    public long pendingBatchAgeSeconds(java.time.Instant now) {
        java.time.Instant openedAt = accumulator.openedAt();
        return openedAt == null ? -1 : java.time.Duration.between(openedAt, now).toSeconds();
    }

    /** Oldest block buffered but not yet committed; -1 when nothing is pending. */
    public long pendingBatchOldestBlock() {
        return accumulator.firstBlock();
    }

    /** Observability for the batching policy actually in force. */
    public ProjectionBatchPolicy batchPolicy() {
        return batchPolicy;
    }

    public ProjectionBatchAccumulator.Stats batchStats() {
        return accumulator.stats();
    }

    /** Oldest slot this batch still requires; -1 when it requires nothing retained. */
    private static long oldestRequiredSlot(ProjectionBatch batch) {
        return batch.artifacts().stream()
                .mapToLong(ref -> ref.oldestRequiredSlot())
                .min()
                .orElse(-1L);
    }

    public ProjectionBackpressure backpressure() {
        return ProjectionBackpressure.evaluate(store.stats(identity.requiredSections()), bounds);
    }

    public ProjectionOutboxStats stats() {
        return store.stats(identity.requiredSections());
    }
}
