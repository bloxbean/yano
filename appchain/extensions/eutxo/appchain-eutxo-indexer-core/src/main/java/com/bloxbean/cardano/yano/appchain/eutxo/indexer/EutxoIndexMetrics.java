package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, label-free counters shared by the coordinator and local read
 * model. The application layer decides which fixed chain/provider labels to
 * add when publishing them through Micrometer.
 */
public final class EutxoIndexMetrics {
    private final LongAdder applyCount = new LongAdder();
    private final LongAdder applyNanos = new LongAdder();
    private final LongAdder queryCount = new LongAdder();
    private final LongAdder queryNanos = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder rollbacks = new LongAdder();

    void recordApply(long nanos) {
        applyCount.increment();
        applyNanos.add(Math.max(0, nanos));
    }

    void recordQuery(long nanos) {
        queryCount.increment();
        queryNanos.add(Math.max(0, nanos));
    }

    void recordFailure() {
        failures.increment();
    }

    public void recordRollback() {
        rollbacks.increment();
    }

    public long applyCount() {
        return applyCount.sum();
    }

    public long applyNanos() {
        return applyNanos.sum();
    }

    public long queryCount() {
        return queryCount.sum();
    }

    public long queryNanos() {
        return queryNanos.sum();
    }

    public long failures() {
        return failures.sum();
    }

    public long rollbacks() {
        return rollbacks.sum();
    }
}
