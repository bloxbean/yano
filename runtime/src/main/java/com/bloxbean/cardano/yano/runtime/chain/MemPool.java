package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;

import java.util.List;
import java.util.Set;

public interface MemPool {
    // Add a transaction to the mempool and return the created mempool transaction
    MemPoolTransaction addTransaction(byte[] txBytes);

    // Get the next transaction to process (FIFO)
    MemPoolTransaction getNextTransaction();

    // Check if the mempool is empty
    boolean isEmpty();

    // Get the current size of the mempool
    int size();

    // Get the current stored transaction bytes.
    long byteSize();

    // Check whether the mempool already contains a transaction hash
    boolean contains(String txHash);

    // Get a transaction by hash without removing it.
    MemPoolTransaction getTransaction(String txHash);

    // Snapshot transactions in insertion order without removing them.
    List<MemPoolTransaction> snapshotTransactions(int maxCount, long maxBytes);

    // Clear the mempool
    void clear();

    /** Remove transactions confirmed in a block. Returns count removed. */
    int removeByTxHashes(Set<String> txHashes);

    /** Evict the oldest N transactions. Returns actual count evicted. */
    int evictOldest(int count);

    /** Evict oldest transactions until byteSize() is at most maxBytes. Returns actual count evicted. */
    int evictOldestUntilBytesAtMost(long maxBytes);

    /** Remove transactions inserted before the given timestamp. Returns count removed. */
    int removeOlderThan(long beforeEpochMillis);
}
