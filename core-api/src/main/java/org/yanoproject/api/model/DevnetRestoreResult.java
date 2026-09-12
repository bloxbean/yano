package org.yanoproject.api.model;

/**
 * Result of restoring a devnet chain-state snapshot.
 */
public record DevnetRestoreResult(
    long slot,
    long blockNumber
) {}
