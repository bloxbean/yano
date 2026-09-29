package org.yanoproject.runtime.mempool;

import java.util.function.Consumer;

/**
 * Where the mempool gets its canonical bases (ADR-056 §3, §6). The production source reads the
 * {@code CanonicalStateGate}; tests supply fixed ledger worlds.
 *
 * <p><b>Lock order.</b> {@link #acquire()} may take the canonical gate's read lock, so the mempool calls it only
 * while it does not hold its mutation lane. {@link #current()} is a volatile read that never takes the gate, so it
 * is the freshness check used under the lane.</p>
 */
public interface MempoolBaseSource {

    /**
     * Acquires a base of the current canonical generation, ticked to the admission slot. The caller owns the one
     * reference of the result. Never returns {@code null}: when no state can be captured the base is
     * {@linkplain MempoolBase#isUnavailable() unavailable} and every read of its view is unavailable.
     */
    MempoolBase acquire();

    /** @return the published canonical generation and target epoch, without taking the gate */
    CanonicalMark current();

    /** @return true when the calling thread holds the canonical gate (lock-order assertions) */
    boolean isHeldByCurrentThread();

    /**
     * Registers {@code listener} for canonical publications (forward blocks, rollbacks, producer boundary
     * sections), called after the gate is released.
     *
     * @return a handle that unregisters it
     */
    AutoCloseable onPublication(Consumer<CanonicalMark> listener);
}
