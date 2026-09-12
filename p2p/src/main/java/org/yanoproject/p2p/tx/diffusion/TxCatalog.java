package org.yanoproject.p2p.tx.diffusion;

import org.yanoproject.api.model.MemPoolTransaction;

/**
 * Read-only transaction catalogue used by tx diffusion.
 */
public interface TxCatalog {
    boolean contains(String txHash);

    MemPoolTransaction getTransaction(String txHash);

    static TxCatalog empty() {
        return new TxCatalog() {
            @Override
            public boolean contains(String txHash) {
                return false;
            }

            @Override
            public MemPoolTransaction getTransaction(String txHash) {
                return null;
            }
        };
    }
}
