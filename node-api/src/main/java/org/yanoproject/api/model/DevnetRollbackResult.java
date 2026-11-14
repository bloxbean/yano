package org.yanoproject.api.model;

/**
 * Result of a controlled devnet rollback.
 */
public record DevnetRollbackResult(
    long slot,
    long blockNumber
) {}
