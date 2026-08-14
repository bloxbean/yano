package com.bloxbean.cardano.yano.api.appchain.sink;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;

/**
 * A destination for finalized app blocks (ADR app-layer/006 E3.2) — a webhook,
 * Kafka, a database, an index. The framework drives delivery strictly in height
 * order with a persisted per-sink cursor, so a sink only implements the actual
 * write and reports success; the cursor advances only on success (at-least-once).
 */
public interface FinalizedStreamSink extends AutoCloseable {

    /** Stable id; the delivery cursor is keyed by it, so keep it stable. */
    String id();

    /**
     * Deliver one finalized block. Return {@code true} on success (cursor
     * advances) or {@code false}/throw to retry the same block next tick — a
     * thrown failure is surfaced by exception class only as the sink's
     * {@code lastError}/{@code lastErrorType}; its message is not exposed
     * because it may contain configuration or credentials.
     * Must be idempotent-friendly: the same block may be delivered again after
     * a crash between the write and the cursor commit.
     */
    boolean deliver(AppBlock block) throws Exception;

    /**
     * Optional cursor key used by a previous implementation of this sink, so an
     * in-place upgrade can migrate persisted delivery progress instead of
     * restarting from the tip. Return {@code null} when there is no legacy key.
     */
    default String legacyCursorKey() {
        return null;
    }

    @Override
    default void close() {
    }
}
