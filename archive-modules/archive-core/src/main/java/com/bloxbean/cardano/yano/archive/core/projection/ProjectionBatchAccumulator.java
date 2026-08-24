package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionManifest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Accumulates eligible envelopes until the batch policy says commit.
 *
 * <p>The important property is that every safety bound is evaluated <strong>before</strong>
 * rows are materialised. Row counts come from the section manifests already stored in the
 * outbox, so the accumulator knows exactly how many {@code ArchiveRow} objects a batch would
 * produce without building any of them. Bounding after materialisation would be too late:
 * the allocation that must be prevented would already have happened.
 *
 * <p>Envelopes are added one at a time and the accumulator refuses one that would breach a
 * ceiling, leaving it for the next batch. A single envelope larger than a ceiling is still
 * accepted when the batch is empty, so an oversized block makes progress rather than
 * deadlocking the consumer.
 */
public final class ProjectionBatchAccumulator {

    private final ProjectionBatchPolicy policy;
    private final List<ProjectionEnvelope> envelopes = new ArrayList<>();

    private long rows;
    private long encodedBytes;
    private Instant openedAt;

    /**
     * Set when an offer was refused for space. The batch cannot grow further, so it must
     * commit even if neither the block target nor the freshness deadline has been reached;
     * otherwise the coordinator would spin re-offering an envelope that can never fit.
     */
    private ProjectionBatchDecision.Reason saturatedBy;

    // --- observability, accumulated across the accumulator's lifetime -----------
    private long largestEnvelopeBytes;
    private long largestEnvelopeRows;
    private long oversizedSingletons;
    private long singletonHighWatermarkBytes;
    private long largestRejectedSingletonBytes;
    private final java.util.EnumMap<ProjectionBatchDecision.Reason, Long> flushReasons =
            new java.util.EnumMap<>(ProjectionBatchDecision.Reason.class);

    public ProjectionBatchAccumulator(ProjectionBatchPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public boolean isEmpty() {
        return envelopes.isEmpty();
    }

    public int size() {
        return envelopes.size();
    }

    /** First block held, or -1 when empty. */
    public long firstBlock() {
        return envelopes.isEmpty() ? -1 : envelopes.get(0).header().blockNumber();
    }

    /**
     * When the oldest buffered envelope entered the batch, or null when empty.
     *
     * <p>This is the clock the freshness deadline runs on, and the basis for reporting how
     * long a finality-eligible envelope has waited before reaching the sink.
     */
    public Instant openedAt() {
        return envelopes.isEmpty() ? null : openedAt;
    }

    /** Last block held, or -1 when empty. */
    public long lastBlock() {
        return envelopes.isEmpty() ? -1 : envelopes.get(envelopes.size() - 1).header().blockNumber();
    }

    public List<ProjectionEnvelope> envelopes() {
        return List.copyOf(envelopes);
    }

    public long rows() {
        return rows;
    }

    public long encodedBytes() {
        return encodedBytes;
    }

    public long estimatedHeapBytes() {
        return rows * policy.estimatedHeapBytesPerRow();
    }

    /** Outcome of offering an envelope to the batch. */
    public enum Offer {
        /** Added to the batch. */
        ACCEPTED,
        /** Would breach a ceiling; flush the batch and offer it again. */
        REJECTED_FULL,
        /**
         * Alone, this envelope exceeds the absolute singleton safety guard. Processing it
         * would risk an OOM, and skipping it would lose projection data, so the coordinator
         * must pause visibly with diagnostics.
         */
        REJECTED_UNSAFE
    }

    /**
     * Offer an envelope.
     *
     * <p>A single envelope larger than a normal ceiling is still accepted when the batch is
     * empty, so a dense block makes progress rather than deadlocking the consumer — but only
     * up to the absolute safety guard.
     */
    public Offer offer(ProjectionEnvelope envelope, Instant now) {
        long envelopeRows = rowsOf(envelope);
        long envelopeBytes = bytesOf(envelope);

        if (envelopes.isEmpty()) {
            if (!policy.singletonWithinSafetyGuard(envelopeBytes, envelopeRows)) {
                largestRejectedSingletonBytes = Math.max(largestRejectedSingletonBytes, envelopeBytes);
                return Offer.REJECTED_UNSAFE;
            }
            if (envelopeBytes > policy.maxEncodedBytes() || envelopeRows > policy.effectiveMaxRows()) {
                oversizedSingletons++;
                singletonHighWatermarkBytes = Math.max(singletonHighWatermarkBytes,
                        envelopeRows * policy.estimatedHeapBytesPerRow());
            }
        } else {
            if (envelopes.size() + 1 > policy.maxBlocks()) return saturate(ProjectionBatchDecision.Reason.MAX_BLOCKS);
            if (encodedBytes + envelopeBytes > policy.maxEncodedBytes()) {
                return saturate(ProjectionBatchDecision.Reason.MAX_BYTES);
            }
            if (rows + envelopeRows > policy.effectiveMaxRows()) {
                return saturate(ProjectionBatchDecision.Reason.MAX_ROWS);
            }
        }

        if (envelopes.isEmpty()) openedAt = now;
        envelopes.add(envelope);
        rows += envelopeRows;
        encodedBytes += envelopeBytes;

        largestEnvelopeBytes = Math.max(largestEnvelopeBytes, envelopeBytes);
        largestEnvelopeRows = Math.max(largestEnvelopeRows, envelopeRows);
        return Offer.ACCEPTED;
    }

    private Offer saturate(ProjectionBatchDecision.Reason reason) {
        saturatedBy = reason;
        return Offer.REJECTED_FULL;
    }

    /**
     * Decide whether to commit what has accumulated.
     *
     * @param nearTip        whether the producer is at the chain tip, selecting the regime
     * @param moreAvailable  whether more eligible envelopes are waiting. Reported for metrics
     *                       and retained in the signature, but deliberately not a flush
     *                       trigger: an intermittent gap in arrivals is normal near tip and
     *                       must not cause a commit
     */
    public ProjectionBatchDecision decide(boolean nearTip, boolean moreAvailable, Instant now) {
        int blocks = envelopes.size();
        long heap = estimatedHeapBytes();
        if (blocks == 0) return ProjectionBatchDecision.accumulate(0, 0, 0, 0);

        if (blocks >= policy.maxBlocks()) {
            return ProjectionBatchDecision.flush(ProjectionBatchDecision.Reason.MAX_BLOCKS,
                    blocks, rows, encodedBytes, heap);
        }
        if (encodedBytes >= policy.maxEncodedBytes()) {
            return ProjectionBatchDecision.flush(ProjectionBatchDecision.Reason.MAX_BYTES,
                    blocks, rows, encodedBytes, heap);
        }
        if (rows >= policy.maxRows()) {
            return ProjectionBatchDecision.flush(ProjectionBatchDecision.Reason.MAX_ROWS,
                    blocks, rows, encodedBytes, heap);
        }
        if (heap >= policy.maxEstimatedHeapBytes()) {
            return ProjectionBatchDecision.flush(ProjectionBatchDecision.Reason.MAX_HEAP,
                    blocks, rows, encodedBytes, heap);
        }
        // A refused offer means no further envelope fits. Commit rather than wait for a
        // target that can no longer be reached by growing.
        if (saturatedBy != null) {
            return ProjectionBatchDecision.flush(saturatedBy, blocks, rows, encodedBytes, heap);
        }
        if (blocks >= policy.minBlocks(nearTip)) {
            return ProjectionBatchDecision.flush(ProjectionBatchDecision.Reason.MIN_BLOCKS,
                    blocks, rows, encodedBytes, heap);
        }

        Duration waited = Duration.between(openedAt, now);
        if (waited.compareTo(policy.maxLinger(nearTip)) >= 0) {
            return ProjectionBatchDecision.flush(ProjectionBatchDecision.Reason.LINGER_EXPIRED,
                    blocks, rows, encodedBytes, heap);
        }

        // "Nothing more is eligible right now" is deliberately NOT a flush reason near tip.
        // Blocks arrive intermittently, so treating a momentary gap as a reason to commit
        // would flush on almost every arrival and recreate the tiny-file problem. The batch
        // stays durably in the outbox until the target or the freshness deadline says commit.
        return ProjectionBatchDecision.accumulate(blocks, rows, encodedBytes, heap);
    }

    /**
     * Flush regardless of thresholds, for an explicit operational action.
     *
     * <p>Deliberately <strong>not</strong> used on shutdown. Accumulated envelopes are still
     * durable and unacknowledged in the outbox, so a restart simply re-reads them; writing a
     * small Parquet file purely to empty an in-memory list would create exactly the fragment
     * this policy exists to avoid, for no durability benefit.
     */
    public ProjectionBatchDecision forceFlush() {
        return ProjectionBatchDecision.flush(ProjectionBatchDecision.Reason.FORCED,
                envelopes.size(), rows, encodedBytes, estimatedHeapBytes());
    }

    /**
     * Observability for the memory invariant.
     *
     * @param largestEnvelopeEncodedBytes  largest single encoded envelope seen
     * @param largestEnvelopeRows          largest single envelope row count seen
     * @param oversizedSingletons          envelopes accepted alone because they exceeded a
     *                                     normal batch ceiling
     * @param singletonHighWatermarkBytes  estimated working set of the largest such singleton
     * @param largestRejectedSingletonBytes largest envelope refused by the absolute guard;
     *                                     non-zero means the node paused rather than risking OOM
     */
    public record Stats(long largestEnvelopeEncodedBytes, long largestEnvelopeRows,
                        long oversizedSingletons, long singletonHighWatermarkBytes,
                        long largestRejectedSingletonBytes,
                        java.util.Map<ProjectionBatchDecision.Reason, Long> flushReasons) { }

    public synchronized Stats stats() {
        return new Stats(largestEnvelopeBytes, largestEnvelopeRows, oversizedSingletons,
                singletonHighWatermarkBytes, largestRejectedSingletonBytes,
                java.util.Map.copyOf(flushReasons));
    }

    /**
     * Record why a batch actually flushed.
     *
     * <p>Batch-size distribution is uninterpretable without this: a run dominated by
     * {@code LINGER_EXPIRED} is behaving as designed near tip, while one dominated by
     * {@code MAX_ROWS} or {@code MAX_HEAP} means the bounds are mis-sized for the chain.
     */
    public synchronized void recordFlush(ProjectionBatchDecision decision) {
        if (decision.flush()) flushReasons.merge(decision.reason(), 1L, Long::sum);
    }

    /** Effective row ceiling after the heap bound is applied; exposed for tests. */
    long effectiveMaxRowsForTest() {
        return policy.effectiveMaxRows();
    }

    public void reset() {
        envelopes.clear();
        rows = 0;
        encodedBytes = 0;
        openedAt = null;
        saturatedBy = null;
    }

    /** Row count from the stored manifests; no materialisation required. */
    static long rowsOf(ProjectionEnvelope envelope) {
        long total = 0;
        for (ProjectionSectionManifest manifest : envelope.header().sections()) total += manifest.rowCount();
        return total;
    }

    static long bytesOf(ProjectionEnvelope envelope) {
        long total = 0;
        for (ProjectionSection section : envelope.sections()) total += section.byteCount();
        return total;
    }
}
