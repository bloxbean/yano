package com.bloxbean.cardano.yano.archive.api;

/** Physical capabilities exposed without leaking backend implementation details. */
public record ArchiveCapabilities(
        boolean snapshotReads,
        boolean secondaryIndexes,
        boolean concurrentExternalReaders,
        boolean onlineCompaction,
        boolean onlineBackup) {
}
