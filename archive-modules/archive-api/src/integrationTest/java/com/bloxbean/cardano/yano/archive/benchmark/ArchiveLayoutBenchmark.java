package com.bloxbean.cardano.yano.archive.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

/**
 * Repeatable Phase-0 probe for a single Yaci Store address_utxo Parquet range.
 * It is intentionally outside production source sets and never runs in normal tests.
 */
public final class ArchiveLayoutBenchmark {
    private ArchiveLayoutBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("usage: -Pfixture=/trusted/path/*.parquet [-PbenchmarkOutput=/tmp/result]");
        }
        String fixture = args[0];
        Path output = args.length == 2 ? Path.of(args[1]) : Files.createTempDirectory("yano-archive-layout-");
        Files.createDirectories(output);
        for (String ownedFile : new String[]{"wide.parquet", "output.parquet", "asset.parquet",
                "datum.parquet", "script.parquet",
                "wide.sqlite", "normalized.sqlite"}) {
            Files.deleteIfExists(output.resolve(ownedFile));
        }

        Class.forName("org.duckdb.DuckDBDriver");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("SET memory_limit='256MB'");
            statement.execute("SET threads=1");
            statement.execute("CREATE VIEW fixture AS SELECT * FROM read_parquet('" + sql(fixture) + "')");

            try (ResultSet rs = statement.executeQuery("""
                    SELECT count(*) wide_rows,
                           count(DISTINCT (tx_hash, output_index)) outputs,
                           count(*) FILTER (WHERE asset_unit = 'lovelace') ada_rows,
                           count(*) FILTER (WHERE asset_unit <> 'lovelace') asset_rows,
                           count(*) FILTER (WHERE inline_datum IS NOT NULL) datum_rows,
                           count(*) FILTER (WHERE script_ref IS NOT NULL) script_rows
                    FROM fixture
                    """)) {
                rs.next();
                System.out.printf("rows.wide=%d%nrows.outputs=%d%nrows.ada=%d%nrows.assets=%d%nrows.datum=%d%nrows.script=%d%n",
                        rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6));
            }

            statement.execute("""
                    CREATE TABLE normalized_output AS
                    SELECT tx_hash, output_index, any_value(owner_addr) owner_addr,
                           any_value(owner_stake_credential) owner_stake_credential,
                           max(quantity) FILTER (WHERE asset_unit = 'lovelace') lovelace,
                           any_value(data_hash) data_hash, any_value(reference_script_hash) reference_script_hash,
                           any_value(TRY_CAST(epoch AS BIGINT)) epoch,
                           any_value(TRY_CAST(slot AS BIGINT)) slot, any_value(block_hash) block_hash,
                           any_value(block_time) block_time
                    FROM fixture GROUP BY tx_hash, output_index
                    """);
            statement.execute("""
                    CREATE TABLE normalized_asset AS
                    SELECT tx_hash, output_index, policy_id, asset_name, quantity,
                           TRY_CAST(epoch AS BIGINT) epoch, TRY_CAST(slot AS BIGINT) slot
                    FROM fixture WHERE asset_unit <> 'lovelace'
                    """);
            statement.execute("""
                    CREATE TABLE normalized_datum AS
                    SELECT data_hash, any_value(inline_datum) cbor
                    FROM fixture WHERE data_hash IS NOT NULL AND inline_datum IS NOT NULL
                    GROUP BY data_hash
                    """);
            statement.execute("""
                    CREATE TABLE normalized_script AS
                    SELECT reference_script_hash script_hash, any_value(script_ref) cbor
                    FROM fixture WHERE reference_script_hash IS NOT NULL AND script_ref IS NOT NULL
                    GROUP BY reference_script_hash
                    """);

            String txHash;
            long minSlot;
            long maxSlot;
            try (ResultSet rs = statement.executeQuery(
                    "SELECT any_value(tx_hash), min(TRY_CAST(slot AS BIGINT)), max(TRY_CAST(slot AS BIGINT)) FROM fixture")) {
                rs.next();
                txHash = rs.getString(1);
                minSlot = rs.getLong(2);
                maxSlot = rs.getLong(3);
            }

            long parquetMillis = timed(() -> {
                statement.execute("COPY fixture TO '" + sql(output.resolve("wide.parquet").toString())
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
                statement.execute("COPY normalized_output TO '" + sql(output.resolve("output.parquet").toString())
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
                statement.execute("COPY normalized_asset TO '" + sql(output.resolve("asset.parquet").toString())
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
                statement.execute("COPY normalized_datum TO '" + sql(output.resolve("datum.parquet").toString())
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
                statement.execute("COPY normalized_script TO '" + sql(output.resolve("script.parquet").toString())
                        + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
            });
            System.out.printf("parquet.write_ms=%d%nparquet.wide_bytes=%d%nparquet.normalized_bytes=%d%n",
                    parquetMillis, Files.size(output.resolve("wide.parquet")),
                    Files.size(output.resolve("output.parquet")) + Files.size(output.resolve("asset.parquet"))
                            + Files.size(output.resolve("datum.parquet")) + Files.size(output.resolve("script.parquet")));

            String wideSqlitePath = output.resolve("wide.sqlite").toString();
            String normalizedSqlitePath = output.resolve("normalized.sqlite").toString();
            statement.execute("INSTALL sqlite");
            statement.execute("LOAD sqlite");
            statement.execute("ATTACH '" + sql(wideSqlitePath) + "' AS wide_db (TYPE SQLITE)");
            statement.execute("ATTACH '" + sql(normalizedSqlitePath) + "' AS normalized_db (TYPE SQLITE)");
            long sqliteMillis = timed(() -> {
                statement.execute("CREATE TABLE wide_db.wide AS SELECT * FROM memory.main.fixture");
                statement.execute("USE wide_db");
                statement.execute("CREATE INDEX wide_tx_hash ON wide(tx_hash)");
                statement.execute("USE memory");
                statement.execute("CREATE TABLE normalized_db.normalized_output AS SELECT * FROM memory.main.normalized_output");
                statement.execute("CREATE TABLE normalized_db.normalized_asset AS SELECT * FROM memory.main.normalized_asset");
                statement.execute("CREATE TABLE normalized_db.normalized_datum AS SELECT * FROM memory.main.normalized_datum");
                statement.execute("CREATE TABLE normalized_db.normalized_script AS SELECT * FROM memory.main.normalized_script");
                statement.execute("USE normalized_db");
                statement.execute("CREATE UNIQUE INDEX output_outpoint ON normalized_output(tx_hash, output_index)");
                statement.execute("CREATE INDEX output_stake ON normalized_output(owner_stake_credential, slot)");
                statement.execute("CREATE INDEX asset_outpoint ON normalized_asset(tx_hash, output_index)");
                statement.execute("CREATE UNIQUE INDEX datum_hash ON normalized_datum(data_hash)");
                statement.execute("CREATE UNIQUE INDEX script_hash ON normalized_script(script_hash)");
                statement.execute("CREATE INDEX output_slot ON normalized_output(slot)");
                statement.execute("USE memory");
            });
            long sqliteWidePointNanos = bestOf(25, () -> query(statement,
                    "SELECT count(*) FROM wide_db.wide WHERE tx_hash='" + sql(txHash) + "'"));
            long sqliteNormalizedPointNanos = bestOf(25, () -> query(statement,
                    "SELECT count(*) FROM normalized_db.normalized_output WHERE tx_hash='" + sql(txHash) + "'"));
            long sqliteRangeNanos = bestOf(10, () -> query(statement,
                    "SELECT count(*) FROM normalized_db.normalized_output WHERE slot BETWEEN " + minSlot
                            + " AND " + Math.min(maxSlot, minSlot + 10_000)));
            statement.execute("DETACH wide_db");
            statement.execute("DETACH normalized_db");
            long directSqliteWidePointNanos;
            long directSqliteNormalizedPointNanos;
            long directSqliteRangeNanos;
            try (Connection wideSqlite = DriverManager.getConnection("jdbc:sqlite:" + wideSqlitePath);
                 Connection normalizedSqlite = DriverManager.getConnection("jdbc:sqlite:" + normalizedSqlitePath)) {
                directSqliteWidePointNanos = bestOf(25, () -> query(wideSqlite,
                        "SELECT count(*) FROM wide WHERE tx_hash=?", txHash));
                directSqliteNormalizedPointNanos = bestOf(25, () -> query(normalizedSqlite,
                        "SELECT count(*) FROM normalized_output WHERE tx_hash=?", txHash));
                directSqliteRangeNanos = bestOf(10, () -> query(normalizedSqlite,
                        "SELECT count(*) FROM normalized_output WHERE slot BETWEEN ? AND ?",
                        minSlot, Math.min(maxSlot, minSlot + 10_000)));
            }
            System.out.printf("sqlite.write_ms=%d%nsqlite.wide_bytes=%d%nsqlite.normalized_bytes=%d%n"
                            + "sqlite.wide_point_best_us=%d%nsqlite.normalized_point_best_us=%d%n"
                            + "sqlite.normalized_range_best_us=%d%n"
                            + "sqlite.direct_wide_point_best_us=%d%nsqlite.direct_normalized_point_best_us=%d%n"
                            + "sqlite.direct_normalized_range_best_us=%d%n",
                    sqliteMillis, Files.size(Path.of(wideSqlitePath)), Files.size(Path.of(normalizedSqlitePath)),
                    sqliteWidePointNanos / 1_000, sqliteNormalizedPointNanos / 1_000, sqliteRangeNanos / 1_000,
                    directSqliteWidePointNanos / 1_000, directSqliteNormalizedPointNanos / 1_000,
                    directSqliteRangeNanos / 1_000);
            long pointNanos = bestOf(25, () -> query(statement,
                    "SELECT count(*) FROM read_parquet('" + sql(output.resolve("output.parquet").toString())
                            + "') WHERE tx_hash='" + sql(txHash) + "'"));
            long rangeNanos = bestOf(10, () -> query(statement,
                    "SELECT count(*) FROM read_parquet('" + sql(output.resolve("output.parquet").toString())
                            + "') WHERE slot BETWEEN " + minSlot + " AND " + Math.min(maxSlot, minSlot + 10_000)));
            System.out.printf("parquet.point_best_us=%d%nparquet.range_best_us=%d%noutput=%s%n",
                    pointNanos / 1_000, rangeNanos / 1_000, output);
        }
    }

    private static long timed(SqlAction action) throws Exception {
        Instant start = Instant.now();
        action.run();
        return Duration.between(start, Instant.now()).toMillis();
    }

    private static long bestOf(int count, SqlAction action) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            action.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    private static void query(Statement statement, String sql) throws Exception {
        try (ResultSet ignored = statement.executeQuery(sql)) {
            while (ignored.next()) { /* consume */ }
        }
    }

    private static void query(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet ignored = statement.executeQuery()) {
                while (ignored.next()) { /* consume */ }
            }
        }
    }

    private static String sql(String value) {
        return value.replace("'", "''");
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
