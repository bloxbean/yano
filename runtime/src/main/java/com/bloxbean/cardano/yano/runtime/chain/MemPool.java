package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yano.api.model.MemPoolTransaction;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yaci.events.api.VetoableEvent;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public interface MemPool {
    @FunctionalInterface
    interface AdmissionValidator {
        List<VetoableEvent.Rejection> validate(byte[] txBytes, String txHash,
                                               Function<Outpoint, Utxo> resolver);
    }

    /**
     * Validate and commit one transaction against a stable
     * mempool-plus-canonical UTXO view.
     */
    MempoolAdmissionResult tryAdmit(byte[] txBytes,
                                    Function<Outpoint, Utxo> canonicalResolver,
                                    AdmissionValidator validator,
                                    MempoolAdmissionLimits limits,
                                    Consumer<MemPoolTransaction> acceptedListener);

    // Add a transaction to the mempool and return the created mempool transaction
    // Compatibility path for trusted callers. Implementations must still project
    // the transaction and preserve all index invariants.
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

    /** Remove transactions claiming canonically consumed outpoints, with descendants. */
    int removeConflictingInputs(Set<Outpoint> consumedOutpoints);

    /** Remove invalid transactions and all dependent descendants. */
    int removeInvalidated(Set<String> txHashes);

    /** Evict the oldest N transactions. Returns actual count evicted. */
    int evictOldest(int count);

    /** Evict oldest transactions until byteSize() is at most maxBytes. Returns actual count evicted. */
    int evictOldestUntilBytesAtMost(long maxBytes);

    /** Remove transactions inserted before the given timestamp. Returns count removed. */
    int removeOlderThan(long beforeEpochMillis);

    /** Reconcile the ordered overlay after canonical UTXO rollback. */
    int revalidate(Function<Outpoint, Utxo> canonicalResolver);

    /** Read-only semantic status; does not expose mutable indexes. */
    MempoolStats stats();
}
