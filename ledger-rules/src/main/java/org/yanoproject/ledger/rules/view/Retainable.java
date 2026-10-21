package org.yanoproject.ledger.rules.view;

/**
 * A reference-counted resource, such as a {@code CanonicalSnapshot} backing a {@link LedgerView}.
 *
 * <p>Each holder calls {@link #retain()} when it takes a reference and {@link #release()} exactly
 * once when it is done; the resource is freed when the count reaches zero. Handing a view to
 * another owner transfers a reference and never closes the base (ADR-056 §3). Views over a
 * {@code Retainable} base do not retain it implicitly; their owner manages the base's lifetime
 * through {@link OverlayLedgerView#base()}.</p>
 */
public interface Retainable {

    /**
     * Takes one more reference.
     *
     * @return this, for chaining
     * @throws IllegalStateException if the resource has already been freed
     */
    Retainable retain();

    /** Drops one reference and frees the resource when none remain. */
    void release();
}
