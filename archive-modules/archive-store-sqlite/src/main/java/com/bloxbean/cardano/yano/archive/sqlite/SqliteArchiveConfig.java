package com.bloxbean.cardano.yano.archive.sqlite;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record SqliteArchiveConfig(
        Path databasePath,
        Duration acquireTimeout,
        Duration queryTimeout,
        int maxReaders,
        Durability durability) {

    public enum Durability { FULL, NORMAL }

    public SqliteArchiveConfig {
        databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        Objects.requireNonNull(durability, "durability");
        if (acquireTimeout.isZero() || acquireTimeout.isNegative()
                || queryTimeout.isZero() || queryTimeout.isNegative() || maxReaders < 1) {
            throw new IllegalArgumentException("invalid SQLite archive configuration");
        }
    }

    public static SqliteArchiveConfig defaults(Path historyDirectory) {
        return new SqliteArchiveConfig(historyDirectory.resolve("history.sqlite"),
                Duration.ofSeconds(5), Duration.ofSeconds(10), 4, Durability.FULL);
    }
}
