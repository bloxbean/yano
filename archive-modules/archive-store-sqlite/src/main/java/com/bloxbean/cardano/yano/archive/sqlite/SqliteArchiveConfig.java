package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * @param acquireTimeout SQLite {@code busy_timeout} and bounded internal waits
 * @param waitPolicy     warn-versus-stuck thresholds for writer and reader waiting.
 *                       SQLite has no nested DuckDB lease, so it adopts the timeout
 *                       and status semantics without the DuckLake resource-order rule.
 */
public record SqliteArchiveConfig(
        Path databasePath,
        Duration acquireTimeout,
        Duration queryTimeout,
        int maxReaders,
        Durability durability,
        ArchiveWaitPolicy waitPolicy) {

    public enum Durability { FULL, NORMAL }

    public SqliteArchiveConfig {
        databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        Objects.requireNonNull(durability, "durability");
        Objects.requireNonNull(waitPolicy, "waitPolicy");
        if (acquireTimeout.isZero() || acquireTimeout.isNegative()
                || queryTimeout.isZero() || queryTimeout.isNegative() || maxReaders < 1) {
            throw new IllegalArgumentException("invalid SQLite archive configuration");
        }
    }

    public SqliteArchiveConfig(Path databasePath, Duration acquireTimeout, Duration queryTimeout,
                               int maxReaders, Durability durability) {
        this(databasePath, acquireTimeout, queryTimeout, maxReaders, durability,
                ArchiveWaitPolicy.defaults());
    }

    public static SqliteArchiveConfig defaults(Path historyDirectory) {
        return new SqliteArchiveConfig(historyDirectory.resolve("history.sqlite"),
                Duration.ofSeconds(30), Duration.ofSeconds(10), 4, Durability.FULL,
                ArchiveWaitPolicy.defaults());
    }
}
