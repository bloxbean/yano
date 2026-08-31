package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.duckdb.DuckDBDriver;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckDbManagerTest {
    @TempDir Path temp;

    @Test
    void defaultsReserveOneSteadyContextDuringBulkWork() throws Exception {
        try (var manager = new DuckDbManager(DuckDbManagerConfig.defaults(temp), DuckDbExtensionLoader.none());
             var bulk = manager.acquire(DuckDbWorkload.BULK_CATCH_UP, Duration.ofSeconds(2));
             var steady = manager.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(2))) {

            assertThat(setting(bulk, "memory_limit")).isEqualTo("128.0 MiB");
            assertThat(setting(steady, "threads")).isEqualTo("1");
            assertThat(setting(steady, "preserve_insertion_order")).isEqualTo("false");
            assertThat(setting(steady, "autoinstall_known_extensions")).isEqualTo("false");
            assertThat(setting(steady, "autoload_known_extensions")).isEqualTo("false");
            try (var statement = steady.createBoundedStatement(Duration.ofSeconds(3))) {
                assertThat(statement.getQueryTimeout()).isEqualTo(3);
            }

            assertThatThrownBy(() -> manager.acquire(DuckDbWorkload.BULK_CATCH_UP,
                    Duration.ofMillis(50)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("bulk slot");
        }
    }

    @Test
    void packagedSignedExtensionsLoadWithoutInstall() throws Exception {
        Path extracted = temp.resolve("extensions");
        try (var manager = new DuckDbManager(DuckDbManagerConfig.defaults(temp.resolve("work")),
                new PackagedDuckDbExtensionLoader(extracted));
             var lease = manager.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(10));
             var statement = lease.connection().createStatement();
             var result = statement.executeQuery("SELECT extension_name FROM duckdb_extensions() "
                     + "WHERE loaded AND extension_name IN ('ducklake', 'sqlite_scanner') ORDER BY extension_name")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("ducklake");
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("sqlite_scanner");
            assertThat(result.next()).isFalse();
        }
    }

    @Test
    void acquisitionUsesTheManagersExplicitDriverInstance() throws Exception {
        AtomicInteger directConnectCalls = new AtomicInteger();
        DuckDBDriver driver = new DuckDBDriver() {
            @Override
            public Connection connect(String url, Properties properties) throws SQLException {
                directConnectCalls.incrementAndGet();
                return super.connect(url, properties);
            }
        };

        try (var manager = new DuckDbManager(DuckDbManagerConfig.defaults(temp.resolve("direct")),
                DuckDbExtensionLoader.none(), ArchiveWaitPolicy.defaults(), driver);
             var lease = manager.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(2))) {
            assertThat(lease.connection()).isNotNull();
            assertThat(directConnectCalls).hasValue(1);
        }
    }

    @Test
    void driverDecliningTheDuckDbUrlFailsExplicitly() {
        DuckDBDriver decliningDriver = new DuckDBDriver() {
            @Override
            public Connection connect(String url, Properties properties) {
                return null;
            }
        };
        try (var manager = new DuckDbManager(DuckDbManagerConfig.defaults(temp.resolve("declined")),
                DuckDbExtensionLoader.none(), ArchiveWaitPolicy.defaults(), decliningDriver)) {
            assertThatThrownBy(() -> manager.acquire(DuckDbWorkload.STEADY,
                    Duration.ofSeconds(2)))
                    .isInstanceOf(SQLException.class)
                    .hasMessage("DuckDB driver declined JDBC URL: jdbc:duckdb:");
        }
    }

    @Test
    void missingNativeSidecarNamesTheExpectedFileAndCompleteDistributionRemedy() {
        Path expected = temp.resolve("distribution/libduckdb_java.so_osx_universal")
                .toAbsolutePath();
        DuckDBDriver missingSidecarDriver = new DuckDBDriver() {
            @Override
            public Connection connect(String url, Properties properties) {
                FileNotFoundException missing = new FileNotFoundException(
                        "DuckDB JNI library not found, path: '" + expected + "'");
                throw new ExceptionInInitializerError(new RuntimeException(missing));
            }
        };
        try (var manager = new DuckDbManager(DuckDbManagerConfig.defaults(temp.resolve("missing")),
                DuckDbExtensionLoader.none(), ArchiveWaitPolicy.defaults(), missingSidecarDriver)) {
            assertThatThrownBy(() -> manager.acquire(DuckDbWorkload.STEADY,
                    Duration.ofSeconds(2)))
                    .isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("Start Yano from the complete native distribution")
                    .hasMessageContaining(expected.toString());
        }
    }

    @Test
    void invalidBudgetsCannotConsumeTheSteadyReservation() {
        var workload = new DuckDbWorkloadConfig(128L * 1024 * 1024, 1);
        assertThatThrownBy(() -> new DuckDbManagerConfig(256L * 1024 * 1024,
                2, 2, temp, 0, workload, workload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interruptionReturnsBulkPermitAcquiredBeforeTotalPermit() throws Exception {
        var workload = new DuckDbWorkloadConfig(64L * 1024 * 1024, 1);
        var config = new DuckDbManagerConfig(256L * 1024 * 1024,
                2, 1, temp.resolve("interrupt"), 0, workload, workload);
        try (var manager = new DuckDbManager(config, DuckDbExtensionLoader.none());
             var steadyOne = manager.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(2));
             var steadyTwo = manager.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(2))) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = Thread.ofPlatform().start(() -> {
                try {
                    manager.acquire(DuckDbWorkload.BULK_CATCH_UP, Duration.ofSeconds(10)).close();
                } catch (Throwable error) {
                    failure.set(error);
                }
            });

            Semaphore bulkPermits = semaphore(manager, "bulkPermits");
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (bulkPermits.availablePermits() != 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(bulkPermits.availablePermits()).isZero();
            waiter.interrupt();
            waiter.join(2_000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(SQLException.class)
                    .hasMessageContaining("interrupted");
            assertThat(bulkPermits.availablePermits()).isEqualTo(1);
        }
    }

    @Test
    void bothCapacityGatesShareOneDeadlineInsteadOfEachGettingTheFullWait() throws Exception {
        // Regression: each gate used to receive the full policy timeout, so a
        // bulk acquisition could wait nearly the threshold for the bulk gate and
        // then the whole threshold again for the total gate.
        var workload = new DuckDbWorkloadConfig(64L * 1024 * 1024, 1);
        var config = new DuckDbManagerConfig(256L * 1024 * 1024, 2, 1,
                temp.resolve("shared-deadline"), 0, workload, workload);
        Duration bound = Duration.ofSeconds(1);
        try (var manager = new DuckDbManager(config, DuckDbExtensionLoader.none());
             var steadyOne = manager.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(2));
             var steadyTwo = manager.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(2))) {
            assertThat(steadyOne).isNotNull();
            assertThat(steadyTwo).isNotNull();

            // Hold the bulk permit directly so releasing it does not also free a
            // total permit; total stays exhausted by the two steady leases.
            Semaphore bulkPermits = semaphore(manager, "bulkPermits");
            bulkPermits.acquire();

            // Free the bulk gate partway through the budget. A correct
            // implementation still fails at the shared deadline; the old one
            // would then start a fresh full wait on the total gate.
            Thread releaser = Thread.ofPlatform().start(() -> {
                try {
                    Thread.sleep(bound.toMillis() * 6 / 10);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                bulkPermits.release();
            });

            long startedAt = System.nanoTime();
            assertThatThrownBy(() -> manager.acquire(DuckDbWorkload.BULK_CATCH_UP, bound))
                    .isInstanceOf(SQLException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
            releaser.join(5_000);

            assertThat(waited).as("waited %s against a %s bound", waited, bound)
                    .isLessThan(bound.multipliedBy(3).dividedBy(2));
            // The failed acquisition must not have kept either permit.
            assertThat(bulkPermits.availablePermits()).isEqualTo(1);
            assertThat(semaphore(manager, "totalPermits").availablePermits()).isZero();
        }
    }

    private String setting(DuckDbLease lease, String name) throws SQLException {
        try (var statement = lease.connection().createStatement();
             var result = statement.executeQuery("SELECT current_setting('" + name + "')")) {
            result.next();
            return result.getString(1);
        }
    }

    private static Semaphore semaphore(DuckDbManager manager, String name) throws Exception {
        var field = DuckDbManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Semaphore) field.get(manager);
    }
}
