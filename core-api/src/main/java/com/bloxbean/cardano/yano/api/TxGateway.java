package com.bloxbean.cardano.yano.api;

/**
 * Transaction submission surface for tx-gateway and producer roles.
 */
public interface TxGateway {
    String submitTransaction(byte[] txCbor);

    /**
     * Whether the transaction is currently waiting in this node's mempool.
     * Lets the API layer distinguish "pending" from "unknown" for wallet
     * tx-status queries (ADR-033 M2).
     */
    default boolean isTransactionInMemPool(String txHash) {
        return false;
    }
}
