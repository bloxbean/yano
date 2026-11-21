package org.yanoproject.api.model;

/**
 * Metadata about a chain state snapshot.
 */
public record SnapshotInfo(
    String name,
    long slot,
    long blockNumber,
    long createdAt
) {}
