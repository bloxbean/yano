package com.bloxbean.cardano.yano.archive.ducklake;

import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 0: establishes whether the DuckDB Appender can write directly to
 * a DuckLake-attached catalog table (shape "a"), or whether a native staging
 * table plus one {@code INSERT ... SELECT} is required (shape "b").
 *
 * <p>This is the blocking question for Phase 2. The ADR is explicit that a
 * benchmark against a plain DuckDB table proves nothing, because the Appender
 * writes through DuckDB's native storage layer while DuckLake manages Parquet
 * files through its extension. The test therefore targets a real attached
 * DuckLake catalog.
 */
class DuckLakeAppenderFeasibilityTest {
    @TempDir Path temp;

    private DuckLakeArchiveConfig config() {
        return new DuckLakeArchiveConfig(temp.resolve("catalog.sqlite"), temp.resolve("data"),
                Duration.ofSeconds(5), 10, 10, 16L * 1024 * 1024, 10_000,
                Duration.ofHours(168), Duration.ofHours(24));
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement sql = connection.createStatement()) {
            sql.execute("SET autoinstall_known_extensions = false");
            sql.execute("SET autoload_known_extensions = false");
        }
        new PackagedDuckDbExtensionLoader(temp.resolve("extensions")).load(connection);
        DuckLakeSql.attach(connection, config(), null, false);
        return connection;
    }

    private static long count(Connection connection, String table) throws Exception {
        try (Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void establishesWhetherAppenderCanTargetDuckLakeDirectly() throws Exception {
        try (Connection connection = open()) {
            try (Statement sql = connection.createStatement()) {
                sql.execute("CREATE TABLE " + DuckLakeSql.ALIAS + ".feasibility (a BIGINT, b VARCHAR)");
                sql.execute("CREATE TABLE staging (a BIGINT, b VARCHAR)");
            }

            // Shape (a): Appender straight at the DuckLake-attached catalog.
            String directFailure = null;
            long directRows = 0;
            try (var appender = ((DuckDBConnection) connection)
                    .createAppender(DuckLakeSql.ALIAS, "main", "feasibility")) {
                appender.beginRow();
                appender.append(1L);
                appender.append("direct");
                appender.endRow();
                appender.flush();
            } catch (Exception e) {
                directFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (directFailure == null) {
                directRows = count(connection, DuckLakeSql.ALIAS + ".feasibility");
            }

            // Shape (b): Appender into a native DuckDB table, then one bulk copy.
            String stagedFailure = null;
            long stagedRows = 0;
            try {
                try (var appender = ((DuckDBConnection) connection).createAppender("main", "staging")) {
                    appender.beginRow();
                    appender.append(2L);
                    appender.append("staged");
                    appender.endRow();
                    appender.flush();
                }
                try (Statement sql = connection.createStatement()) {
                    sql.execute("INSERT INTO " + DuckLakeSql.ALIAS + ".feasibility SELECT * FROM staging");
                }
                stagedRows = count(connection, DuckLakeSql.ALIAS + ".feasibility");
            } catch (Exception e) {
                stagedFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            System.out.println("=== ADR-038 Phase 0: Appender feasibility ===");
            System.out.println("shape (a) direct-to-DuckLake : "
                    + (directFailure == null ? "SUPPORTED, rows=" + directRows : "UNSUPPORTED -> " + directFailure));
            System.out.println("shape (b) native staging     : "
                    + (stagedFailure == null ? "SUPPORTED, rows=" + stagedRows : "UNSUPPORTED -> " + stagedFailure));

            // Shape (b) is the fallback the ADR assumes always works; if this ever
            // fails, Phase 2 has no viable Appender path at all.
            assertThat(stagedFailure).as("native staging + INSERT SELECT must work").isNull();
            assertThat(stagedRows).isPositive();

            // Phase 0 finding, recorded as an assertion so a driver or extension
            // upgrade that removes it fails loudly rather than silently forcing
            // Phase 2 back onto the staging round-trip.
            assertThat(directFailure)
                    .as("ADR-038 Phase 0: the Appender must be able to target a DuckLake-attached "
                            + "table directly; losing this reverts Phase 2 to shape (b) and revives "
                            + "the staging round-trip that Phase 3 plans to remove")
                    .isNull();
            assertThat(directRows).as("direct append landed in the DuckLake table").isEqualTo(1);
        }
    }
}
