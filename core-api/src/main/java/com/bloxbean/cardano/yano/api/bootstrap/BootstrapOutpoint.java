package com.bloxbean.cardano.yano.api.bootstrap;

/**
 * A specific UTXO reference (transaction output) to bootstrap.
 *
 * @param txHash      transaction hash (hex)
 * @param outputIndex output index within the transaction
 */
public record BootstrapOutpoint(
        String txHash,
        int outputIndex
) {}
