package com.bloxbean.cardano.yano.runtime.tx;

import java.util.List;

/**
 * Narrow transaction-pool view used by block producers when selecting
 * transactions for a locally produced block.
 */
public interface BlockTransactionSelector {
    boolean hasPendingTransactions();

    List<byte[]> drainForBlock();
}
