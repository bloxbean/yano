package com.bloxbean.cardano.yano.api.model;

/**
 * Target selector for a controlled devnet rollback.
 */
public record DevnetRollbackTarget(
    Long slot,
    Long blockNumber,
    Integer count
) {
    public static DevnetRollbackTarget slot(long slot) {
        return new DevnetRollbackTarget(slot, null, null);
    }
}
