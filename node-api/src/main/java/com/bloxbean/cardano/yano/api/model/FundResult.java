package com.bloxbean.cardano.yano.api.model;

/**
 * Result of a faucet fund operation — the synthetic UTXO reference.
 */
public record FundResult(
    String txHash,
    int index,
    long lovelace
) {}
