package com.bloxbean.cardano.yano.api;

/**
 * Transaction submission surface for tx-gateway and producer roles.
 */
public interface TxGateway {
    String submitTransaction(byte[] txCbor);
}
