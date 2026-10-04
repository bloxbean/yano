package org.yanoproject.runtime.chain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Sequence lock over canonical chain-index mutations (app-layer ADR-038, D8b rule 7). The value is odd while any
 * operation that removes or replaces a canonical index entry is in flight, on any thread, and even otherwise. A
 * reader that sees the same even value before and after a pass therefore knows that no such mutation overlapped the
 * pass. Overlapping writers on different threads keep it odd until the last one ends; nested mutations on one thread
 * count once. Appending new entries is not a mutation in this sense.
 */
final class CanonicalMutationSequence {
    private final AtomicLong value = new AtomicLong();
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);
    private int activeWriters; // guarded by this

    long current() {
        return value.get();
    }

    /** Enters a mutation; pair with {@link #end()} in a {@code finally} block. */
    void begin() {
        if (depth.get()[0]++ == 0) {
            synchronized (this) {
                if (activeWriters++ == 0) {
                    value.incrementAndGet();
                }
            }
        }
    }

    void end() {
        int[] nesting = depth.get();
        if (nesting[0] <= 0) {
            throw new IllegalStateException("Canonical mutation end without begin");
        }
        if (--nesting[0] == 0) {
            synchronized (this) {
                if (--activeWriters == 0) {
                    value.incrementAndGet();
                }
            }
        }
    }
}
