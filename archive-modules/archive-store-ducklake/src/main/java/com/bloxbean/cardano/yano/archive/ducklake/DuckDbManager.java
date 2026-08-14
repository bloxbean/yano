package com.bloxbean.cardano.yano.archive.ducklake;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens short-lived, resource-bounded DuckDB contexts. Bulk work can never take
 * the steady-state reservation, and no context may enable runtime downloads.
 */
public final class DuckDbManager implements AutoCloseable {
    private final DuckDbManagerConfig config;
    private final DuckDbExtensionLoader extensionLoader;
    private final Semaphore totalPermits;
    private final Semaphore bulkPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DuckDbManager(DuckDbManagerConfig config, DuckDbExtensionLoader extensionLoader) {
        this.config = Objects.requireNonNull(config, "config");
        this.extensionLoader = Objects.requireNonNull(extensionLoader, "extensionLoader");
        this.totalPermits = new Semaphore(config.maxConcurrentQueries(), true);
        this.bulkPermits = new Semaphore(config.maxConcurrentBulkJobs(), true);
        try {
            Files.createDirectories(config.tempDirectory());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot create DuckDB temp directory", e);
        }
    }

    public DuckDbLease acquire(DuckDbWorkload workload, Duration wait) throws SQLException {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(wait, "wait");
        if (wait.isNegative() || wait.isZero()) throw new IllegalArgumentException("wait must be positive");
        if (closed.get()) throw new IllegalStateException("DuckDbManager is closed");

        boolean bulk = workload == DuckDbWorkload.BULK_CATCH_UP;
        boolean bulkAcquired = false;
        boolean totalAcquired = false;
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            if (bulk) {
                bulkAcquired = tryAcquire(bulkPermits, deadline);
                if (!bulkAcquired) throw new SQLException("timed out waiting for a DuckDB bulk slot");
            }
            totalAcquired = tryAcquire(totalPermits, deadline);
            if (!totalAcquired) throw new SQLException("timed out waiting for a DuckDB query slot");
            if (closed.get()) throw new IllegalStateException("DuckDbManager is closed");

            Connection connection = DriverManager.getConnection("jdbc:duckdb:");
            try {
                configure(connection, workload);
                extensionLoader.load(connection);
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
            boolean releaseBulk = bulk;
            return new DuckDbLease(connection, () -> {
                totalPermits.release();
                if (releaseBulk) bulkPermits.release();
            });
        } catch (InterruptedException e) {
            if (totalAcquired) totalPermits.release();
            if (bulkAcquired) bulkPermits.release();
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for a DuckDB slot", e);
        } catch (SQLException | RuntimeException | Error e) {
            if (totalAcquired) totalPermits.release();
            if (bulkAcquired) bulkPermits.release();
            throw e;
        }
    }

    public DuckDbManagerConfig config() {
        return config;
    }

    private boolean tryAcquire(Semaphore semaphore, long deadlineNanos) throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 && semaphore.tryAcquire(remaining, TimeUnit.NANOSECONDS);
    }

    private void configure(Connection connection, DuckDbWorkload workload) throws SQLException {
        DuckDbWorkloadConfig workloadConfig = workload == DuckDbWorkload.STEADY
                ? config.steadyState() : config.bulkCatchUp();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET autoinstall_known_extensions = false");
            statement.execute("SET autoload_known_extensions = false");
            statement.execute("SET enable_progress_bar = false");
            statement.execute("SET memory_limit = '" + workloadConfig.memoryLimitBytes() + "B'");
            statement.execute("SET threads = " + workloadConfig.threads());
            statement.execute("SET preserve_insertion_order = false");
            statement.execute("SET temp_directory = '" + sqlString(config.tempDirectory().toAbsolutePath().normalize().toString()) + "'");
            statement.execute("SET max_temp_directory_size = '" + config.maxTempDirectoryBytes() + "B'");
        }
    }

    private static String sqlString(String value) {
        return value.replace("'", "''");
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
