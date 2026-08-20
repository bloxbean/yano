package com.bloxbean.cardano.yano.archive.core.projection;

import java.time.Duration;
import java.util.Objects;

/**
 * Adaptive commit batching (ADR-039).
 *
 * <p>Small Parquet files are prevented at their source rather than merged afterwards. The
 * first preprod sync committed whatever was eligible the instant it became eligible, which —
 * because the sink outpaces the producer — meant a few hundred blocks per commit and 67,603
 * files averaging 56 KB. Waiting costs nothing: the outbox is durable, so a batch that
 * lingers is not a batch at risk.
 *
 * <p>Two regimes with opposite constraints:
 *
 * <ul>
 *   <li><strong>bootstrap</strong> — blocks arrive in the thousands per second, so a block
 *       count governs and a large one costs almost no latency;</li>
 *   <li><strong>near tip</strong> — blocks arrive at the chain rate, so a block count would
 *       stall for days and a freshness interval must govern instead.</li>
 * </ul>
 *
 * <p>Every bound is a <em>ceiling</em> that forces an early flush; only {@code minBlocks} and
 * {@code maxLinger} decide when a batch is worth committing at all. That asymmetry matters:
 * a safety bound must never be able to make a batch larger.
 *
 * <p>Physical file granularity is deliberately not a commit interval. Logical visibility is
 * governed here; Parquet file size is governed by compaction.
 *
 * <h2>Memory invariant</h2>
 *
 * <p>Stated precisely, because it is <strong>not</strong> a fully configurable hard heap
 * bound and should not be described as one:
 *
 * <ul>
 *   <li>the <strong>encoded outbox payload</strong> retained for a selected batch is bounded
 *       by {@link #maxEncodedBytes()}. The selected {@code ProjectionEnvelope} objects and
 *       their encoded chunks do occupy memory up to that bound;</li>
 *   <li>transaction decoding and row emission are <strong>streaming</strong>: facts are
 *       decoded and released one at a time, and chunks are never concatenated;</li>
 *   <li><strong>derived rows are not retained for the whole batch</strong> — batch size no
 *       longer contributes to decoded-fact or derived-row retention;</li>
 *   <li>UTXO decoding retains at most <strong>one block's</strong> decoded fact graph, because
 *       row derivation resolves each output's address through a map built from that section's
 *       address list;</li>
 *   <li>a <strong>singleton</strong> envelope may exceed the normal batch estimate. It stays
 *       bounded by one valid Cardano block and that block's deterministic projection
 *       expansion, not by configuration — and beyond
 *       {@link #singletonWithinSafetyGuard(long, long)} the node pauses visibly rather than
 *       risking an OOM.</li>
 * </ul>
 *
 * <p>Existing evidence: a production preprod sync observed ~2.96 MB as the largest retained
 * working set. That is an observation on one chain, not a protocol maximum.
 */
public record ProjectionBatchPolicy(int minBlocksBootstrap,
                                    int minBlocksNearTip,
                                    int maxBlocks,
                                    Duration maxLingerBootstrap,
                                    Duration maxLingerNearTip,
                                    long maxEncodedBytes,
                                    long maxRows,
                                    long maxEstimatedHeapBytes,
                                    int estimatedHeapBytesPerRow,
                                    long maxSingletonEncodedBytes,
                                    long maxSingletonRows) {

    public ProjectionBatchPolicy {
        Objects.requireNonNull(maxLingerBootstrap, "maxLingerBootstrap");
        Objects.requireNonNull(maxLingerNearTip, "maxLingerNearTip");
        if (minBlocksBootstrap < 1 || minBlocksNearTip < 1) {
            throw new IllegalArgumentException("minimum block counts must be positive");
        }
        if (maxBlocks < minBlocksBootstrap || maxBlocks < minBlocksNearTip) {
            throw new IllegalArgumentException("maxBlocks must be at least every minimum");
        }
        if (maxEncodedBytes < 1 || maxRows < 1 || maxEstimatedHeapBytes < 1) {
            throw new IllegalArgumentException("safety bounds must be positive");
        }
        if (estimatedHeapBytesPerRow < 1) {
            throw new IllegalArgumentException("estimatedHeapBytesPerRow must be positive");
        }
        if (maxSingletonEncodedBytes < 1 || maxSingletonRows < 1) {
            throw new IllegalArgumentException("singleton safety guards must be positive");
        }
        if (maxLingerBootstrap.isNegative() || maxLingerNearTip.isNegative()) {
            throw new IllegalArgumentException("linger must not be negative");
        }
    }

    /**
     * Defaults chosen from the first preprod sync, and deliberately not presented as
     * universally safe — the bootstrap minimum in particular is a starting point to measure,
     * not a proven constant.
     *
     * <p>10,000 bootstrap blocks accrue in ~8 s at the measured 1,193 blocks/s, giving ~20x
     * fewer commits.
     *
     * <p>Near tip the block count is a <strong>preferred target</strong> (50 envelopes), not a
     * freshness requirement, and the 15-minute deadline is the hard bound. At normal Cardano
     * production the deadline typically fires first, around 40-50 blocks; that is the expected
     * outcome rather than a miss. A near-tip minimum of 1 would flush every eligible block on
     * arrival and recreate the tiny-file problem this policy exists to prevent, and against an
     * archive already ~24 h behind by finality the added latency is immaterial.
     *
     * <p>{@code estimatedHeapBytesPerRow} is intentionally conservative: measured output is
     * ~709 bytes/block as Parquet but rows carry uncompressed {@code byte[]} hashes,
     * addresses and CBOR in heap.
     */
    public static ProjectionBatchPolicy defaults() {
        return new ProjectionBatchPolicy(
                10_000, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15),
                256L << 20,          // encoded outbox bytes
                2_000_000,           // materialised rows
                512L << 20,          // estimated materialised heap
                600,                 // conservative bytes per row
                64L << 20,           // singleton guard: encoded bytes for ONE envelope
                4_000_000);          // singleton guard: rows for ONE envelope
    }

    public int minBlocks(boolean nearTip) {
        return nearTip ? minBlocksNearTip : minBlocksBootstrap;
    }

    public Duration maxLinger(boolean nearTip) {
        return nearTip ? maxLingerNearTip : maxLingerBootstrap;
    }

    /** Rows that fit the heap bound at the configured per-row estimate. */
    public long heapBoundedRows() {
        return Math.max(1, maxEstimatedHeapBytes / estimatedHeapBytesPerRow);
    }

    /** The effective row ceiling: the tighter of the explicit row bound and the heap bound. */
    public long effectiveMaxRows() {
        return Math.min(maxRows, heapBoundedRows());
    }

    /**
     * Whether a single envelope is safe to process on its own.
     *
     * <p>An envelope larger than a normal batch ceiling is still accepted alone, so an
     * unusually dense block makes progress rather than deadlocking the consumer. But
     * "unusually dense" has a limit: a section derives from one valid Cardano block, and a
     * projection of one block cannot legitimately reach tens of millions of rows. Something
     * that does indicates corruption or a format change, and materialising it would risk an
     * OOM that takes the node down.
     *
     * <p>These guards are set well above any plausible block so they never fire in normal
     * operation, and are configurable because a protocol upgrade may legitimately raise block
     * limits.
     */
    public boolean singletonWithinSafetyGuard(long encodedBytes, long rows) {
        return encodedBytes <= maxSingletonEncodedBytes && rows <= maxSingletonRows;
    }
}
