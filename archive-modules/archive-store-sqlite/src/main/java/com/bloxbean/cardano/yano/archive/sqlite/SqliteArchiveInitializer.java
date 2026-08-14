package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

final class SqliteArchiveInitializer {
    static final String MIGRATION_LOCATION = "classpath:db/migration/history-sqlite";
    static final String SCHEMA_HISTORY_TABLE = "yano_archive_schema_history";

    private final SqliteArchiveConfig config;

    SqliteArchiveInitializer(SqliteArchiveConfig config) {
        this.config = config;
    }

    void initialize(ArchiveIdentity expected) {
        String url = "jdbc:sqlite:" + config.databasePath();
        Flyway flyway = Flyway.configure()
                .dataSource(url, null, null)
                .locations(MIGRATION_LOCATION)
                .table(SCHEMA_HISTORY_TABLE)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();
        flyway.validate();
        try {
            SqliteArchiveSql.initializeConnection(config);
            try (Connection connection = SqliteArchiveSql.open(config, false)) {
                verifySchemaMatchesContract(connection);
                verifyOrCreateIdentity(connection, expected);
            }
        } catch (SQLException e) {
            throw new ArchiveStoreException("cannot initialize SQLite archive", e);
        }
    }

    private void verifySchemaMatchesContract(Connection connection) throws SQLException {
        for (var table : SqliteArchiveSql.tables().values()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT name FROM pragma_table_info(?) ORDER BY cid")) {
                query.setString(1, table.physicalName());
                try (ResultSet columns = query.executeQuery()) {
                    for (var expected : table.columns()) {
                        if (!columns.next() || !expected.name().equals(columns.getString(1))) {
                            throw new ArchiveStoreException("SQLite migration schema differs from archive contract for "
                                    + table.physicalName() + '.' + expected.name());
                        }
                    }
                    if (columns.next()) {
                        throw new ArchiveStoreException("SQLite migration has unexpected columns for "
                                + table.physicalName());
                    }
                }
            }
        }
    }

    private void verifyOrCreateIdentity(Connection connection, ArchiveIdentity expected) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet result = sql.executeQuery(
                "SELECT archive_id, engine, schema_version, network_magic, genesis_hash FROM archive_identity")) {
            if (result.next()) {
                ArchiveIdentity actual = new ArchiveIdentity(UUID.fromString(result.getString(1)), result.getString(2),
                        result.getInt(3), result.getInt(4), result.getString(5));
                if (!actual.equals(expected) || result.next()) {
                    throw new ArchiveStoreException("SQLite archive identity mismatch: expected=" + expected
                            + ", actual=" + actual);
                }
                verifyProjectionVersions(connection);
                return;
            }
        }

        connection.setAutoCommit(false);
        try {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO archive_identity VALUES (1, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, expected.archiveId().toString());
                insert.setString(2, expected.engine());
                insert.setInt(3, expected.schemaVersion());
                insert.setInt(4, expected.networkMagic());
                insert.setString(5, expected.genesisHash());
                insert.setLong(6, Instant.now().toEpochMilli());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO archive_schema(dataset, projection_version, installed_at) VALUES (?, ?, ?)")) {
                long now = Instant.now().toEpochMilli();
                for (var entry : ArchiveSchemas.all().entrySet()) {
                    insert.setString(1, entry.getKey().logicalName());
                    insert.setInt(2, entry.getValue().projectionVersion());
                    insert.setLong(3, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void verifyProjectionVersions(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT projection_version FROM archive_schema WHERE dataset=?")) {
            for (var entry : ArchiveSchemas.all().entrySet()) {
                query.setString(1, entry.getKey().logicalName());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next() || result.getInt(1) != entry.getValue().projectionVersion()
                            || result.next()) {
                        throw new ArchiveStoreException("SQLite projection metadata mismatch for " + entry.getKey()
                                + "; rebuild the unreleased preview archive directory");
                    }
                }
            }
        }
    }
}
