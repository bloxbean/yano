package com.bloxbean.cardano.yano.archive.core.config;

import java.time.Duration;

/**
 * ADR-038 Phase 2c settings for bounded ordered UTXO block prefetching.
 *
 * <p><b>Opt-in and serial by default.</b> Parallelism is a literal operator
 * setting and is deliberately never derived from the host CPU count: the
 * prototype must not silently change behaviour when moved between machines, and
 * mainnet validation has not yet been performed.
 *
 * <p>{@code maxInFlightBytes} and {@code estimatedBytesPerBlock} express a
 * <em>conservative estimated</em> in-flight memory budget, not an exact byte
 * ceiling. The estimate must cover raw block bytes, the decoded fact graph, their
 * temporary overlap and future/result bookkeeping.
 *
 * @param enabled                whether ordered prefetching is used at all
 * @param parallelism            decoder threads; 1 isolates prefetch/cache-bypass gains from concurrency gains
 * @param maxInFlightBlocks      strict bound on blocks decoded or awaiting consumption
 * @param maxInFlightBytes       conservative estimated in-flight memory budget
 * @param estimatedBytesPerBlock conservative per-block reservation
 * @param drainTimeout           how long lease release waits for task bodies before failing loudly
 */
public record UtxoPrefetchConfig(boolean enabled, int parallelism, int maxInFlightBlocks,
                                 long maxInFlightBytes, long estimatedBytesPerBlock, Duration drainTimeout) {

    public UtxoPrefetchConfig {
        if (parallelism < 1) {
            throw new IllegalArgumentException("utxo prefetch parallelism must be positive, got " + parallelism);
        }
        if (maxInFlightBlocks < 1) {
            throw new IllegalArgumentException("utxo prefetch max-in-flight-blocks must be positive, got "
                    + maxInFlightBlocks);
        }
        if (estimatedBytesPerBlock < 1) {
            throw new IllegalArgumentException("utxo prefetch estimated-bytes-per-block must be positive, got "
                    + estimatedBytesPerBlock);
        }
        if (maxInFlightBytes < estimatedBytesPerBlock) {
            throw new IllegalArgumentException("utxo prefetch max-in-flight-bytes must admit at least one block: "
                    + maxInFlightBytes + " < " + estimatedBytesPerBlock);
        }
        if (drainTimeout == null || drainTimeout.isNegative() || drainTimeout.isZero()) {
            throw new IllegalArgumentException("utxo prefetch drain-timeout must be positive");
        }
        if (parallelism > maxInFlightBlocks) {
            throw new IllegalArgumentException("utxo prefetch parallelism " + parallelism
                    + " cannot exceed max-in-flight-blocks " + maxInFlightBlocks);
        }
    }

    /**
     * Serial behaviour with the calibrated reservation.
     *
     * <p>The per-block reservation is 4 MiB. This is <b>reservation accounting,
     * not an allocation</b>: nothing pre-allocates 4 MiB per block. It was raised
     * from 2 MiB after mainnet measurement observed a maximum decoded fact graph
     * of 2.96 MiB, which exceeded the old reservation once in 438,350 blocks. The
     * runtime handled that correctly — it surfaced the underestimate, stopped
     * further submission while accounting debt remained, and bounded overshoot to
     * already-active tasks — so this is a calibration change, not a defect fix.
     * 4 MiB leaves roughly 35% headroom over the largest graph observed.
     *
     * <p>The 256 MiB total estimated in-flight budget and the block-count bound are
     * unchanged. At these settings the change costs no prefetch depth: admission
     * requires both {@code pending < 8} and {@code reserved + estimate <= 256 MiB},
     * and 8 blocks reserve only 32 MiB at 4 MiB each, so the <em>count</em> bound
     * is the binding constraint before and after. The byte budget would begin to
     * bind only above 64 in-flight blocks. No throughput change is expected from
     * this calibration.
     */
    public static UtxoPrefetchConfig disabled() {
        return new UtxoPrefetchConfig(false, 1, 8, 256L * 1024 * 1024, 4L * 1024 * 1024, Duration.ofSeconds(120));
    }
}
