package com.bloxbean.cardano.yano.runtime.tx;

/**
 * Admits transactions into the runtime mempool after validation and policy checks.
 */
public interface TransactionAdmission {
    String admitTransaction(byte[] txCbor, String origin);

    int mempoolSize();
}
