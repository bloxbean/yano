package com.bloxbean.cardano.yano.runtime;

/**
 * Non-blocking signal emitted after a header is durably stored.
 *
 * Implementations must not perform body fetch work, RocksDB reads, or any
 * blocking operation on the caller thread.
 */
public interface HeaderAppliedSignal {
    void onHeaderApplied(long slot, long blockNumber, String blockHash);
}
