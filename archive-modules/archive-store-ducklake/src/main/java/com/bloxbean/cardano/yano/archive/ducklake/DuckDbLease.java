package com.bloxbean.cardano.yano.archive.ducklake;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded DuckDB connection lease. */
public final class DuckDbLease implements AutoCloseable {
    private final Connection connection;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    DuckDbLease(Connection connection, Runnable release) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.release = Objects.requireNonNull(release, "release");
    }

    public Connection connection() {
        if (closed.get()) throw new IllegalStateException("DuckDB lease is closed");
        return connection;
    }

    public Statement createBoundedStatement(Duration timeout) throws SQLException {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        Statement statement = connection().createStatement();
        long seconds = Math.max(1, timeout.toSeconds());
        statement.setQueryTimeout(Math.toIntExact(Math.min(Integer.MAX_VALUE, seconds)));
        return statement;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The permit must always be returned; callers observe statement failures directly.
        } finally {
            release.run();
        }
    }
}
