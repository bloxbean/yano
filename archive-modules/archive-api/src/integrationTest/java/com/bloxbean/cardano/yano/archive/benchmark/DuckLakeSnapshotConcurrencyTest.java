package com.bloxbean.cardano.yano.archive.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Hard Phase-0 gate for the request-scoped DuckLake snapshot strategy. */
class DuckLakeSnapshotConcurrencyTest {
    @TempDir
    Path tempDir;

    @Test
    void sqliteCatalogSupportsPinnedReadSnapshotAcrossQueriesWhileWriterCommits() throws Exception {
        String catalog = tempDir.resolve("catalog.sqlite").toString();
        String data = tempDir.resolve("data").toString();

        long pinnedSnapshot;
        try (Connection writer = connection(); Statement sql = writer.createStatement()) {
            loadExtensions(sql);
            attach(sql, "archive", catalog, data, null, false);
            sql.execute("CREATE TABLE archive.events(id BIGINT, value VARCHAR)");
            sql.execute("INSERT INTO archive.events VALUES (1, 'first')");
            pinnedSnapshot = scalarLong(sql, "SELECT id FROM archive.current_snapshot()");
            sql.execute("DETACH archive");
        }

        try (Connection reader = connection(); Statement read = reader.createStatement()) {
            loadExtensions(read);
            attach(read, "archive_read", catalog, data, pinnedSnapshot, true);
            assertThat(scalarLong(read, "SELECT count(*) FROM archive_read.events")).isEqualTo(1);

            try (var executor = Executors.newSingleThreadExecutor()) {
                var commit = executor.submit(() -> {
                    try (Connection writer = connection(); Statement sql = writer.createStatement()) {
                        loadExtensions(sql);
                        attach(sql, "archive_write", catalog, data, null, false);
                        sql.execute("INSERT INTO archive_write.events VALUES (2, 'second')");
                        sql.execute("DETACH archive_write");
                        return true;
                    }
                });
                assertThat(commit.get(10, TimeUnit.SECONDS)).isTrue();
            }

            // Multiple request subqueries stay on the captured generation.
            assertThat(scalarLong(read, "SELECT count(*) FROM archive_read.events")).isEqualTo(1);
            assertThat(scalarLong(read, "SELECT sum(id) FROM archive_read.events")).isEqualTo(1);
            read.execute("DETACH archive_read");
        }

        try (Connection current = connection(); Statement sql = current.createStatement()) {
            loadExtensions(sql);
            attach(sql, "archive", catalog, data, null, true);
            assertThat(scalarLong(sql, "SELECT count(*) FROM archive.events")).isEqualTo(2);
            sql.execute("DETACH archive");
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (Connection connection = connection(); Statement sql = connection.createStatement()) {
                loadExtensions(sql);
                attach(sql, "archive", catalog, data, pinnedSnapshot, true);
                sql.cancel(); // cancellation must be safe even with no active query
                sql.execute("DETACH archive");
            }
        });
    }

    private static Connection connection() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement sql = connection.createStatement()) {
            sql.execute("SET memory_limit='128MB'");
            sql.execute("SET threads=1");
        }
        return connection;
    }

    private static void loadExtensions(Statement sql) throws Exception {
        sql.execute("INSTALL ducklake");
        sql.execute("INSTALL sqlite");
        sql.execute("LOAD ducklake");
        sql.execute("LOAD sqlite");
    }

    private static void attach(Statement sql, String alias, String catalog, String data,
                               Long snapshot, boolean readOnly) throws Exception {
        String options = "DATA_PATH '" + quote(data) + "'"
                + (snapshot == null ? "" : ", SNAPSHOT_VERSION " + snapshot)
                + (readOnly ? ", READ_ONLY" : "");
        sql.execute("ATTACH 'ducklake:sqlite:" + quote(catalog) + "' AS " + alias + " (" + options + ")");
    }

    private static long scalarLong(Statement sql, String query) throws Exception {
        try (ResultSet result = sql.executeQuery(query)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String quote(String value) {
        return value.replace("'", "''");
    }
}
