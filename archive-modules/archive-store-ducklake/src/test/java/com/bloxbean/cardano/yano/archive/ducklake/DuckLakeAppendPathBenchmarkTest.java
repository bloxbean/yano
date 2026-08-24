package com.bloxbean.cardano.yano.archive.ducklake;

import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 0: compares the append paths against a real DuckLake-attached
 * table so Phase 2's implementation choice rests on measurement rather than
 * inference.
 *
 * <p>Variants measured:
 * <ol>
 *   <li><b>current</b> — 256-row {@code INSERT ... VALUES} batches whose
 *       placeholder string is built with the quadratic {@code reduce} in
 *       {@link DuckLakeWriteSession#flushPendingStagingBatch}; </li>
 *   <li><b>stringbuilder</b> — identical, but linear placeholder construction;</li>
 *   <li><b>stringbuilder-10k</b> — linear construction with a larger batch;</li>
 *   <li><b>appender</b> — the DuckDB Appender writing straight at DuckLake.</li>
 * </ol>
 */
class DuckLakeAppendPathBenchmarkTest {
    @TempDir Path temp;

    private static final int ROWS = 50_000;
    private static final int COLUMNS = 10;

    private record Row(byte[] txHash, byte[] blockHash, long blockNumber, long slot, int epoch,
                       long blockTime, int txIndex, boolean valid, long fee, UUID jobId) { }

    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>(ROWS);
        UUID job = new UUID(7L, 9L);
        for (int i = 0; i < ROWS; i++) {
            byte[] tx = new byte[32];
            tx[0] = (byte) i; tx[1] = (byte) (i >>> 8); tx[2] = (byte) (i >>> 16);
            byte[] block = new byte[32];
            block[0] = (byte) (i / 26);
            rows.add(new Row(tx, block, i / 26, i * 3L, i / 21600, 1_600_000_000L + i, i % 26,
                    true, 150_000L + i, job));
        }
        return rows;
    }

    private DuckLakeArchiveConfig config() {
        return new DuckLakeArchiveConfig(temp.resolve("catalog.sqlite"), temp.resolve("data"),
                Duration.ofSeconds(5), 10, 10, 64L * 1024 * 1024, 100_000,
                Duration.ofHours(168), Duration.ofHours(24));
    }

    private Connection open(int threads, long memoryBytes, DuckLakeArchiveConfig config) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement sql = connection.createStatement()) {
            sql.execute("SET autoinstall_known_extensions = false");
            sql.execute("SET autoload_known_extensions = false");
            sql.execute("SET memory_limit = '" + memoryBytes + "B'");
            sql.execute("SET threads = " + threads);
            sql.execute("SET preserve_insertion_order = false");
        }
        new PackagedDuckDbExtensionLoader(temp.resolve("extensions")).load(connection);
        DuckLakeSql.attach(connection, config, null, false);
        return connection;
    }

    private static void createTable(Connection connection, String name) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + DuckLakeSql.ALIAS + '.' + name + " ("
                    + "tx_hash BLOB, block_hash BLOB, block_number BIGINT, slot BIGINT, epoch INTEGER, "
                    + "block_time BIGINT, tx_index INTEGER, valid BOOLEAN, fee BIGINT, archive_job_id UUID)");
        }
    }

    private static long count(Connection connection, String name) throws Exception {
        try (Statement sql = connection.createStatement();
             ResultSet result = sql.executeQuery("SELECT COUNT(*) FROM " + DuckLakeSql.ALIAS + '.' + name)) {
            result.next();
            return result.getLong(1);
        }
    }

    /** Reproduces the current implementation's quadratic placeholder construction. */
    private static String quadraticPlaceholders(int batch) {
        String row = '(' + IntStream.range(0, COLUMNS).mapToObj(i -> "?")
                .reduce((a, b) -> a + ", " + b).orElseThrow() + ')';
        return IntStream.range(0, batch).mapToObj(ignored -> row)
                .reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    private static String linearPlaceholders(int batch) {
        StringBuilder row = new StringBuilder("(");
        for (int i = 0; i < COLUMNS; i++) row.append(i == 0 ? "?" : ", ?");
        row.append(')');
        StringBuilder all = new StringBuilder(row.length() * batch + batch * 2);
        for (int i = 0; i < batch; i++) {
            if (i > 0) all.append(", ");
            all.append(row);
        }
        return all.toString();
    }

    private static long insertBatched(Connection connection, String table, List<Row> rows,
                                      int batchSize, boolean quadratic) throws Exception {
        long start = System.nanoTime();
        String prefix = "INSERT INTO " + DuckLakeSql.ALIAS + '.' + table + " VALUES ";
        for (int offset = 0; offset < rows.size(); offset += batchSize) {
            int size = Math.min(batchSize, rows.size() - offset);
            String placeholders = quadratic ? quadraticPlaceholders(size) : linearPlaceholders(size);
            try (PreparedStatement insert = connection.prepareStatement(prefix + placeholders)) {
                int parameter = 1;
                for (int i = offset; i < offset + size; i++) {
                    Row row = rows.get(i);
                    insert.setObject(parameter++, row.txHash());
                    insert.setObject(parameter++, row.blockHash());
                    insert.setObject(parameter++, row.blockNumber());
                    insert.setObject(parameter++, row.slot());
                    insert.setObject(parameter++, row.epoch());
                    insert.setObject(parameter++, row.blockTime());
                    insert.setObject(parameter++, row.txIndex());
                    insert.setObject(parameter++, row.valid());
                    insert.setObject(parameter++, row.fee());
                    insert.setObject(parameter++, row.jobId());
                }
                insert.executeUpdate();
            }
        }
        return System.nanoTime() - start;
    }

    /**
     * The shape {@link DuckLakeWriteSession} actually uses today: 256-row
     * {@code INSERT ... VALUES} batches into a native staging table created with
     * {@code CREATE TEMP TABLE ... AS SELECT * FROM <target> WHERE false}, then a
     * single {@code INSERT INTO <ducklake target> SELECT * FROM staging}. Every
     * row is therefore written twice.
     */
    private static long insertStaged(Connection connection, String table, List<Row> rows,
                                     int batchSize, boolean quadratic) throws Exception {
        long start = System.nanoTime();
        String staging = "stg_" + table;
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TEMP TABLE " + staging + " AS SELECT * FROM "
                    + DuckLakeSql.ALIAS + '.' + table + " WHERE false");
        }
        String prefix = "INSERT INTO " + staging + " VALUES ";
        for (int offset = 0; offset < rows.size(); offset += batchSize) {
            int size = Math.min(batchSize, rows.size() - offset);
            String placeholders = quadratic ? quadraticPlaceholders(size) : linearPlaceholders(size);
            try (PreparedStatement insert = connection.prepareStatement(prefix + placeholders)) {
                int parameter = 1;
                for (int i = offset; i < offset + size; i++) {
                    Row row = rows.get(i);
                    insert.setObject(parameter++, row.txHash());
                    insert.setObject(parameter++, row.blockHash());
                    insert.setObject(parameter++, row.blockNumber());
                    insert.setObject(parameter++, row.slot());
                    insert.setObject(parameter++, row.epoch());
                    insert.setObject(parameter++, row.blockTime());
                    insert.setObject(parameter++, row.txIndex());
                    insert.setObject(parameter++, row.valid());
                    insert.setObject(parameter++, row.fee());
                    insert.setObject(parameter++, row.jobId());
                }
                insert.executeUpdate();
            }
        }
        try (Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO " + DuckLakeSql.ALIAS + '.' + table + " SELECT * FROM " + staging);
            sql.execute("DROP TABLE " + staging);
        }
        return System.nanoTime() - start;
    }

    /**
     * ADR-038 shape (b): Appender into a native DuckDB staging table, then one
     * {@code INSERT ... SELECT} into DuckLake. Retains the current staging
     * structure — and therefore its replay/verification hooks — while replacing
     * the prepared-statement batches.
     */
    private static long insertAppenderStaged(Connection connection, String table, List<Row> rows)
            throws Exception {
        long start = System.nanoTime();
        String staging = "astg_" + table;
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TEMP TABLE " + staging + " AS SELECT * FROM "
                    + DuckLakeSql.ALIAS + '.' + table + " WHERE false");
        }
        try (var appender = ((DuckDBConnection) connection).createAppender("temp", "main", staging)) {
            for (Row row : rows) {
                appender.beginRow();
                appender.append(row.txHash());
                appender.append(row.blockHash());
                appender.append(row.blockNumber());
                appender.append(row.slot());
                appender.append(row.epoch());
                appender.append(row.blockTime());
                appender.append(row.txIndex());
                appender.append(row.valid());
                appender.append(row.fee());
                appender.append(row.jobId());
                appender.endRow();
            }
            appender.flush();
        }
        try (Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO " + DuckLakeSql.ALIAS + '.' + table + " SELECT * FROM " + staging);
            sql.execute("DROP TABLE " + staging);
        }
        return System.nanoTime() - start;
    }

    private static long insertAppender(Connection connection, String table, List<Row> rows) throws Exception {
        long start = System.nanoTime();
        try (var appender = ((DuckDBConnection) connection).createAppender(DuckLakeSql.ALIAS, "main", table)) {
            for (Row row : rows) {
                appender.beginRow();
                appender.append(row.txHash());
                appender.append(row.blockHash());
                appender.append(row.blockNumber());
                appender.append(row.slot());
                appender.append(row.epoch());
                appender.append(row.blockTime());
                appender.append(row.txIndex());
                appender.append(row.valid());
                appender.append(row.fee());
                appender.append(row.jobId());
                appender.endRow();
            }
            appender.flush();
        }
        return System.nanoTime() - start;
    }

    private static String line(String label, long nanos) {
        double seconds = nanos / 1e9;
        return String.format("  %-20s %8.3f s   %10.0f rows/s", label, seconds, ROWS / seconds);
    }

    private record Profile(String name, int threads, long memoryBytes) { }

    /**
     * Runs every append variant under three configurations: the values the
     * mainnet deployment runs today, ADR-038 Phase 1's conservative defaults, and
     * the opt-in high-throughput profile. Comparing the Appender against the
     * current path at a *different* thread count would overstate the gain.
     */
    @Test
    void comparesAppendPathsAgainstDuckLakeAcrossProfiles() throws Exception {
        List<Row> rows = rows();
        List<Profile> profiles = List.of(
                new Profile("legacy (1t/128MB)", 1, 128L * 1024 * 1024),
                new Profile("default (4t/512MB)", 4, 512L * 1024 * 1024),
                new Profile("highmem (8t/8GB)", 8, 8L * 1024 * 1024 * 1024));

        System.out.println("=== ADR-038 Phase 0: append-path benchmark (DuckLake target, "
                + ROWS + " rows x " + COLUMNS + " cols) ===");
        for (Profile profile : profiles) {
            // A fresh catalog per profile keeps table state and file counts comparable.
            Path root = temp.resolve("p" + profile.threads());
            java.nio.file.Files.createDirectories(root);
            DuckLakeArchiveConfig config = new DuckLakeArchiveConfig(root.resolve("catalog.sqlite"),
                    root.resolve("data"), Duration.ofSeconds(5), 10, 10, 64L * 1024 * 1024, 100_000,
                    Duration.ofHours(168), Duration.ofHours(24));
            try (Connection connection = open(profile.threads(), profile.memoryBytes(), config)) {
                createTable(connection, "bench_staged");
                createTable(connection, "bench_staged_sb10k");
                createTable(connection, "bench_direct");
                createTable(connection, "bench_sb10k");
                createTable(connection, "bench_appender");
                createTable(connection, "bench_appender_staged");

                long staged = insertStaged(connection, "bench_staged", rows, 256, true);
                long stagedSb10k = insertStaged(connection, "bench_staged_sb10k", rows, 10_000, false);
                long direct = insertBatched(connection, "bench_direct", rows, 256, true);
                long stringBuilder10k = insertBatched(connection, "bench_sb10k", rows, 10_000, false);
                long appender = insertAppender(connection, "bench_appender", rows);
                long appenderStaged = insertAppenderStaged(connection, "bench_appender_staged", rows);

                System.out.println("-- " + profile.name());
                System.out.println(line("PRODUCTION staged 256", staged));
                System.out.println(line("staged sb 10k", stagedSb10k));
                System.out.println(line("direct 256 (quad)", direct));
                System.out.println(line("direct sb 10k", stringBuilder10k));
                System.out.println(line("appender (a) direct", appender));
                System.out.println(line("appender (b) staged", appenderStaged));
                System.out.printf("  (a) vs PRODUCTION: %.1fx | (b) vs PRODUCTION: %.1fx | (a) vs (b): %.2fx%n",
                        (double) staged / appender, (double) staged / appenderStaged,
                        (double) appenderStaged / appender);

                assertThat(count(connection, "bench_staged")).isEqualTo(ROWS);
                assertThat(count(connection, "bench_appender")).isEqualTo(ROWS);
            }
        }
    }
}
