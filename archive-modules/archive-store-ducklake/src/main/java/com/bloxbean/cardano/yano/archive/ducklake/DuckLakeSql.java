package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveColumn;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

final class DuckLakeSql {
    static final String ALIAS = "history_lake";
    private DuckLakeSql() {
    }

    static void attach(Connection connection, DuckLakeArchiveConfig config, Long snapshot, boolean readOnly)
            throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("SET ducklake_default_data_inlining_row_limit = 0");
            sql.execute("SET ducklake_max_retry_count = " + config.maxRetries());
            sql.execute("SET ducklake_retry_wait_ms = " + config.retryWaitMillis());
            String options = "DATA_PATH '" + literal(config.dataPath().toString()) + "', DATA_INLINING_ROW_LIMIT 0"
                    + (snapshot == null ? "" : ", SNAPSHOT_VERSION " + snapshot)
                    + (readOnly ? ", READ_ONLY" : "");
            sql.execute("ATTACH 'ducklake:sqlite:" + literal(config.catalogPath().toString())
                    + "' AS " + ALIAS + " (" + options + ")");
        }
    }

    static void detach(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("DETACH " + ALIAS);
        }
    }

    static long currentSnapshot(Connection connection) throws SQLException {
        return scalarLong(connection, "SELECT id FROM " + ALIAS + ".current_snapshot()");
    }

    static long scalarLong(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet result = sql.executeQuery(query)) {
            if (!result.next()) throw new SQLException("query returned no row: " + query);
            return result.getLong(1);
        }
    }

    static Map<String, ArchiveTableSchema> tables() {
        Map<String, ArchiveTableSchema> tables = new LinkedHashMap<>();
        ArchiveSchemas.all().values().forEach(dataset -> dataset.tables().forEach(table -> {
            ArchiveTableSchema previous = tables.putIfAbsent(table.physicalName(), table);
            if (previous != null && !previous.equals(table)) {
                throw new ArchiveStoreException("conflicting logical schema for " + table.physicalName());
            }
        }));
        return Map.copyOf(tables);
    }

    static String createTable(ArchiveTableSchema table) {
        String columns = table.columns().stream().map(DuckLakeSql::column).reduce((a, b) -> a + ", " + b).orElseThrow();
        return "CREATE TABLE IF NOT EXISTS " + ALIAS + '.' + name(table.physicalName()) + " (" + columns + ')';
    }

    static String insertSql(ArchiveTableSchema table) {
        String columns = table.columns().stream().map(ArchiveColumn::name).map(DuckLakeSql::name)
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String values = table.columns().stream().map(ignored -> "?").reduce((a, b) -> a + ", " + b).orElseThrow();
        return "INSERT INTO " + ALIAS + '.' + name(table.physicalName()) + " (" + columns + ") VALUES (" + values + ')';
    }

    static String name(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("untrusted SQL identifier " + value);
        }
        return value;
    }

    static String literal(String value) {
        return value.replace("'", "''");
    }

    private static String column(ArchiveColumn column) {
        String type = switch (column.type()) {
            case BINARY -> "BLOB";
            case TEXT -> "VARCHAR";
            case BOOLEAN -> "BOOLEAN";
            case INT32 -> "INTEGER";
            case INT64 -> "BIGINT";
            case DECIMAL_38 -> "DECIMAL(38,0)";
            case UUID -> "UUID";
        };
        return name(column.name()) + ' ' + type + (column.nullable() ? "" : " NOT NULL");
    }
}
