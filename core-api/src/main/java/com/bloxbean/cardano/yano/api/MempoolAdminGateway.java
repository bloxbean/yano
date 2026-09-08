package com.bloxbean.cardano.yano.api;

import java.util.List;

/** Privileged local eviction. This does not cancel a transaction on the network. */
public interface MempoolAdminGateway {
    MempoolAdminGateway UNAVAILABLE = txHash -> {
        throw new UnsupportedOperationException("Mempool administration unavailable");
    };

    /** Atomically evict the transaction and its pending descendants; absent hashes return an empty list. */
    List<String> evictTransaction(String txHash);
}
