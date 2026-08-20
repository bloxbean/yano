package com.bloxbean.cardano.yano.archive.core.projection;

/** Bounded view of outbox backlog, exposed for health, metrics and backpressure. */
public record ProjectionOutboxStats(long pendingBlocks, long pendingBytes, long pendingRows,
                                    long oldestPendingBlock, long completeThroughBlock,
                                    long acknowledgedThroughBlock) {
    public boolean isEmpty() {
        return pendingBlocks == 0;
    }
}
