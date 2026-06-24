package com.bloxbean.cardano.yano.api.model;

/**
 * Result of restoring a devnet chain-state snapshot.
 */
public record DevnetRestoreResult(
    long slot,
    long blockNumber
) {}
