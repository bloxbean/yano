package com.bloxbean.cardano.yano.p2p.tx.diffusion;

/**
 * Computes a transaction id/hash from transaction CBOR.
 */
@FunctionalInterface
public interface TxHashProvider {
    String txHash(byte[] txCbor);
}
