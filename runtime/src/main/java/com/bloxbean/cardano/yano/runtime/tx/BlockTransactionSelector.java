package com.bloxbean.cardano.yano.runtime.tx;

import java.util.List;

/**
 * Narrow transaction-pool view used by block producers when selecting
 * transactions for a locally produced block.
 */
public interface BlockTransactionSelector {
    boolean hasPendingTransactions();

    List<byte[]> drainForBlock();

    /** Release a successful selection after its block reaches canonical apply. */
    default void blockSelectionCompleted() {
    }

    /** Release a selection when forging or storing its candidate fails. */
    default void blockSelectionFailed() {
    }

    /**
     * Invalidate one selected transaction that can never fit an empty block
     * under the current protocol limits. Implementations should also remove
     * dependent descendants.
     */
    default int invalidateSelectedTransaction(String txHash) {
        return 0;
    }

    /**
     * Compatibility hook for selectors used directly by a producer without a
     * transaction subsystem. The authoritative TxSubsystem path waits for the
     * canonical UTXO acknowledgement instead.
     */
    default void blockCandidatePublished() {
    }
}
