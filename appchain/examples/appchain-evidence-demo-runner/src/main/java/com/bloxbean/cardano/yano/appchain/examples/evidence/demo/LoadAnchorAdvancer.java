package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Throttles best-effort force-anchor requests shared by all load workers. */
final class LoadAnchorAdvancer {
    private static final long MINIMUM_INTERVAL_NANOS = Duration.ofSeconds(2).toNanos();

    private final AtomicLong nextAttemptNanos = new AtomicLong();

    void advance(YanoAuditClient leader) {
        long now = System.nanoTime();
        long next = nextAttemptNanos.get();
        if (now < next || !nextAttemptNanos.compareAndSet(next,
                saturatedAdd(now, MINIMUM_INTERVAL_NANOS))) {
            return;
        }
        try {
            leader.forceAnchor();
        } catch (RuntimeException transientFailure) {
            // The scenario's fixed deadline remains authoritative. A later
            // worker/poll retries after the shared throttle interval.
        }
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }
}
