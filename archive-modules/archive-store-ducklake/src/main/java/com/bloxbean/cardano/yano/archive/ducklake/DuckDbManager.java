package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveResourceDiagnostics;
import com.bloxbean.cardano.yano.archive.api.ArchiveResourceGate;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveStuckOperationException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;
import org.duckdb.DuckDBDriver;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens short-lived, resource-bounded DuckDB contexts. Bulk work can never take
 * the steady-state reservation, and no context may enable runtime downloads.
 */
public final class DuckDbManager implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(DuckDbManager.class.getName());
    private static final String JDBC_URL = "jdbc:duckdb:";

    private final DuckDbManagerConfig config;
    private final DuckDbExtensionLoader extensionLoader;
    private final DuckDBDriver driver;
    // The fair semaphores remain the FIFO mechanism; the gates only add wait
    // diagnostics and the warn-versus-stuck distinction around them.
    private final Semaphore totalPermits;
    private final Semaphore bulkPermits;
    private final ArchiveResourceGate totalGate;
    private final ArchiveResourceGate bulkGate;
    private final ArchiveWaitPolicy waitPolicy;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DuckDbManager(DuckDbManagerConfig config, DuckDbExtensionLoader extensionLoader) {
        this(config, extensionLoader, ArchiveWaitPolicy.defaults());
    }

    public DuckDbManager(DuckDbManagerConfig config, DuckDbExtensionLoader extensionLoader,
                         ArchiveWaitPolicy waitPolicy) {
        this(config, extensionLoader, waitPolicy, loadDriver());
    }

    DuckDbManager(DuckDbManagerConfig config, DuckDbExtensionLoader extensionLoader,
                  ArchiveWaitPolicy waitPolicy, DuckDBDriver driver) {
        this.config = Objects.requireNonNull(config, "config");
        this.extensionLoader = Objects.requireNonNull(extensionLoader, "extensionLoader");
        this.waitPolicy = Objects.requireNonNull(waitPolicy, "waitPolicy");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.totalPermits = new Semaphore(config.maxConcurrentQueries(), true);
        this.bulkPermits = new Semaphore(config.maxConcurrentBulkJobs(), true);
        this.totalGate = new ArchiveResourceGate("duckdb-total", config.maxConcurrentQueries(),
                totalPermits, waitPolicy, DuckDbManager::logWait);
        this.bulkGate = new ArchiveResourceGate("duckdb-bulk", config.maxConcurrentBulkJobs(),
                bulkPermits, waitPolicy, DuckDbManager::logWait);
        try {
            Files.createDirectories(config.tempDirectory());
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot create DuckDB temp directory", e);
        }
    }

    /**
     * Bounded acquisition. Waiting longer than {@code wait} is reported as a
     * timeout rather than as a stuck operation, for callers that must not block
     * for the full stuck threshold.
     */
    public DuckDbLease acquire(DuckDbWorkload workload, Duration wait) throws SQLException {
        Objects.requireNonNull(wait, "wait");
        if (wait.isNegative() || wait.isZero()) throw new IllegalArgumentException("wait must be positive");
        try {
            return acquire(workload, waitPolicy.boundedTo(wait), "bounded-acquire");
        } catch (ArchiveStuckOperationException e) {
            // Bounded saturation, not a backend fault: typed so callers can return
            // unavailable without degrading archive health.
            throw new DuckDbCapacityTimeoutException("timed out waiting for a DuckDB "
                    + (workload == DuckDbWorkload.BULK_CATCH_UP && bulkPermits.availablePermits() == 0
                    ? "bulk" : "query") + " slot", e);
        } catch (ArchiveStoreException e) {
            if (e.getCause() instanceof InterruptedException) {
                throw new SQLException("interrupted waiting for a DuckDB slot", e);
            }
            throw e;
        }
    }

    /**
     * Waits for bounded DuckDB capacity, warning at the policy interval and
     * failing only at the stuck threshold. Callers acquire capacity <em>before</em>
     * the archive writer, so a capacity wait never occupies the writer.
     */
    public DuckDbLease acquire(DuckDbWorkload workload, ArchiveWaitPolicy policy, String operation)
            throws SQLException {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(operation, "operation");
        if (closed.get()) throw new IllegalStateException("DuckDbManager is closed");

        boolean bulk = workload == DuckDbWorkload.BULK_CATCH_UP;
        long bulkTicket = -1;
        long totalTicket = -1;
        // One monotonic deadline for the whole acquisition. Giving each gate the
        // full policy would let a bulk workload wait almost twice the requested
        // bound: nearly the whole threshold on the bulk gate, then the whole
        // threshold again on the total gate.
        long deadline = System.nanoTime() + policy.stuckThreshold().toNanos();
        try {
            if (bulk) bulkTicket = bulkGate.acquire(operation, policy);
            totalTicket = totalGate.acquire(operation, remainingPolicy(policy, deadline, operation));
            if (closed.get()) throw new IllegalStateException("DuckDbManager is closed");

            if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
                long registeredDriverCount = DriverManager.drivers().count();
                LOG.log(System.Logger.Level.DEBUG,
                        "Opening DuckDB connection through explicit DuckDBDriver.connect; "
                                + "DriverManager registered-driver count={0}",
                        registeredDriverCount);
            }
            Connection connection;
            try {
                connection = driver.connect(JDBC_URL, new Properties());
            } catch (LinkageError failure) {
                FileNotFoundException missingLibrary = missingNativeLibrary(failure);
                if (missingLibrary == null) throw failure;
                throw new ArchiveStoreException(
                        "DuckDB native library unavailable. Start Yano from the complete native "
                                + "distribution so its platform JNI sidecar is beside the executable; "
                                + missingLibrary.getMessage(),
                        failure);
            }
            if (connection == null) {
                throw new SQLException("DuckDB driver declined JDBC URL: " + JDBC_URL);
            }
            try {
                configure(connection, workload);
                extensionLoader.load(connection);
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
            long releaseTotal = totalTicket;
            long releaseBulk = bulk ? bulkTicket : -1;
            return new DuckDbLease(connection, () -> {
                totalGate.release(releaseTotal);
                if (releaseBulk >= 0) bulkGate.release(releaseBulk);
            });
        } catch (SQLException | RuntimeException | Error e) {
            if (totalTicket >= 0) totalGate.release(totalTicket);
            if (bulkTicket >= 0) bulkGate.release(bulkTicket);
            throw e;
        }
    }

    /**
     * Policy bounded to whatever is left of the shared deadline. Fails
     * immediately when the first gate consumed the whole budget, so the caller's
     * request bound and the operator's stuck threshold both still hold.
     */
    private ArchiveWaitPolicy remainingPolicy(ArchiveWaitPolicy policy, long deadline, String operation) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new ArchiveStuckOperationException("duckdb-total", operation,
                    policy.stuckThreshold(), "bulk capacity consumed the whole acquisition budget");
        }
        return policy.boundedTo(Duration.ofNanos(remainingNanos));
    }

    public DuckDbManagerConfig config() {
        return config;
    }

    /** Occupancy of the bulk and total capacity gates for the archive status payload. */
    public List<ArchiveResourceDiagnostics.GateUsage> gateUsage() {
        return List.of(bulkGate.usage(), totalGate.usage());
    }

    public Optional<ArchiveResourceDiagnostics.WaitEvent> lastWaitWarning() {
        Optional<ArchiveResourceDiagnostics.WaitEvent> bulk = bulkGate.lastWaitWarning();
        Optional<ArchiveResourceDiagnostics.WaitEvent> total = totalGate.lastWaitWarning();
        if (bulk.isEmpty()) return total;
        if (total.isEmpty()) return bulk;
        return bulk.orElseThrow().at().isAfter(total.orElseThrow().at()) ? bulk : total;
    }

    private static void logWait(String gate, String operation, Duration waited, String holderDetail) {
        LOG.log(System.Logger.Level.WARNING,
                "Archive still waiting for {0} after {1}s while running {2}; {3}",
                gate, waited.toSeconds(), operation, holderDetail);
    }

    private static DuckDBDriver loadDriver() {
        try {
            return new DuckDBDriver();
        } catch (RuntimeException | LinkageError e) {
            throw new ArchiveStoreException("DuckDB driver unavailable", e);
        }
    }

    private static FileNotFoundException missingNativeLibrary(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof FileNotFoundException missing) return missing;
            Throwable cause = current.getCause();
            if (cause == current) return null;
            current = cause;
        }
        return null;
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
