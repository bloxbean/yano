package com.bloxbean.cardano.yano.archive.sqlite;

/** Operator-visible SQLite page usage; values are sampled from one connection. */
public record SqliteStorageStats(
        long pageCount,
        long freePageCount,
        long pageSizeBytes,
        long databaseBytes,
        long reclaimableBytes,
        long walBytes) {
}
