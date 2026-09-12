package org.yanoproject.api.model;

/**
 * Result of a time/slot advance operation.
 */
public record TimeAdvanceResult(
    long newSlot,
    long newBlockNumber,
    int blocksProduced
) {}
