package org.yanoproject.runtime.tx;

/**
 * Admits transactions into the runtime mempool after validation and policy checks.
 */
public interface TransactionAdmission {
    String admitTransaction(byte[] txCbor, String origin);

    int mempoolSize();

    /**
     * @return false while admission would be refused with a retryable status (ADR-056 §6: the ledger-state mempool
     *         is catching up), so peer transactions are not requested
     */
    default boolean admitting() {
        return true;
    }
}
