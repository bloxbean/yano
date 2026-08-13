package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveColumn;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveValueType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class SqliteArchiveSql {
    private SqliteArchiveSql() { }

    static Connection open(SqliteArchiveConfig config, boolean readOnly) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + config.databasePath());
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA foreign_keys=ON");
            sql.execute("PRAGMA busy_timeout=" + config.acquireTimeout().toMillis());
            sql.execute("PRAGMA synchronous=" + config.durability().name());
            if (readOnly) sql.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    static void initializeConnection(SqliteArchiveConfig config) throws SQLException {
        try (Connection connection = open(config, false); Statement sql = connection.createStatement()) {
            String journalMode;
            try (var result = sql.executeQuery("PRAGMA journal_mode=WAL")) {
                result.next();
                journalMode = result.getString(1);
            }
            if (!"wal".equalsIgnoreCase(journalMode)) {
                throw new ArchiveStoreException("SQLite archive could not enable WAL mode");
            }
            sql.execute("PRAGMA wal_autocheckpoint=1000");
        }
    }

    static Map<String, ArchiveTableSchema> tables() {
        Map<String, ArchiveTableSchema> tables = new LinkedHashMap<>();
        ArchiveSchemas.all().values().forEach(dataset -> dataset.tables().forEach(table ->
                tables.putIfAbsent(table.physicalName(), table)));
        return Map.copyOf(tables);
    }

    static String insertSql(ArchiveTableSchema table) {
        String columns = table.columns().stream().map(ArchiveColumn::name).map(SqliteArchiveSql::name)
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        String values = table.columns().stream().map(ignored -> "?")
                .reduce((a, b) -> a + ", " + b).orElseThrow();
        return "INSERT INTO " + name(table.physicalName()) + " (" + columns + ") VALUES (" + values + ')';
    }

    static void bind(PreparedStatement statement, ArchiveTableSchema table, ArchiveRow row) throws SQLException {
        for (int index = 0; index < table.columns().size(); index++) {
            ArchiveColumn column = table.columns().get(index);
            Object value = row.values().get(index);
            if (value == null) {
                statement.setObject(index + 1, null);
            } else if (column.type() == ArchiveValueType.BINARY) {
                statement.setBytes(index + 1, (byte[]) value);
            } else if (column.type() == ArchiveValueType.UUID) {
                statement.setString(index + 1, ((UUID) value).toString());
            } else if (column.type() == ArchiveValueType.BOOLEAN) {
                statement.setInt(index + 1, (Boolean) value ? 1 : 0);
            } else if (column.type() == ArchiveValueType.DECIMAL_38) {
                statement.setString(index + 1, exactUnsignedDecimal(value));
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }

    static long scalarLong(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); var result = sql.executeQuery(query)) {
            if (!result.next()) throw new SQLException("query returned no row: " + query);
            return result.getLong(1);
        }
    }

    static String name(String identifier) {
        if (identifier == null || !identifier.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("untrusted SQL identifier " + identifier);
        }
        return identifier;
    }

    private static String exactUnsignedDecimal(Object value) {
        BigInteger integer;
        try {
            integer = value instanceof BigInteger bigInteger ? bigInteger
                    : value instanceof BigDecimal decimal ? decimal.toBigIntegerExact()
                    : new BigInteger(value.toString());
        } catch (RuntimeException e) {
            throw new ArchiveStoreException("asset quantity is not an exact integer", e);
        }
        if (integer.signum() < 0 || integer.toString().length() > 38) {
            throw new ArchiveStoreException("asset quantity is outside DECIMAL(38,0)");
        }
        return integer.toString();
    }
}
