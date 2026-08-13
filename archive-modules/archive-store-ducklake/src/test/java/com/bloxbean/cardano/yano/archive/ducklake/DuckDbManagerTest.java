package com.bloxbean.cardano.yano.archive.ducklake;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;

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
    void invalidBudgetsCannotConsumeTheSteadyReservation() {
        var workload = new DuckDbWorkloadConfig(128L * 1024 * 1024, 1);
        assertThatThrownBy(() -> new DuckDbManagerConfig(256L * 1024 * 1024,
                2, 2, temp, 0, workload, workload))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String setting(DuckDbLease lease, String name) throws SQLException {
        try (var statement = lease.connection().createStatement();
             var result = statement.executeQuery("SELECT current_setting('" + name + "')")) {
            result.next();
            return result.getString(1);
        }
    }
}
