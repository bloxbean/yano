package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

final class DuckLakeInitializer {
    private final DuckLakeArchiveConfig config;

    DuckLakeInitializer(DuckLakeArchiveConfig config) {
        this.config = config;
    }

    void initialize(Connection connection, ArchiveIdentity expected) throws SQLException {
        createMetadata(connection);
        createDatasetTables(connection);
        configureTables(connection);
        createViews(connection);
        verifyOrCreateIdentity(connection, expected);
    }

    private void createMetadata(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.archive_identity ("
                    + "archive_id UUID NOT NULL, engine VARCHAR NOT NULL, schema_version INTEGER NOT NULL, "
                    + "network_magic INTEGER NOT NULL, genesis_hash VARCHAR NOT NULL, created_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.archive_schema ("
                    + "dataset VARCHAR NOT NULL, projection_version INTEGER NOT NULL, installed_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.archive_commits ("
                    + "job_id UUID NOT NULL, dataset VARCHAR NOT NULL, projection_version INTEGER NOT NULL, "
                    + "source_kind VARCHAR NOT NULL, range_start BIGINT NOT NULL, range_end BIGINT NOT NULL, "
                    + "start_slot BIGINT NOT NULL, start_hash BLOB NOT NULL, end_slot BIGINT NOT NULL, end_hash BLOB NOT NULL, "
                    + "source_state_version VARCHAR NOT NULL, backend_generation BIGINT NOT NULL, "
                    + "ordered_digest VARCHAR NOT NULL, committed_at TIMESTAMP NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.archive_commit_counts ("
                    + "job_id UUID NOT NULL, table_name VARCHAR NOT NULL, row_count BIGINT NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.archive_coverage ("
                    + "job_id UUID NOT NULL, dataset VARCHAR NOT NULL, projection_version INTEGER NOT NULL, "
                    + "source_kind VARCHAR NOT NULL, range_start BIGINT NOT NULL, range_end BIGINT NOT NULL, "
                    + "backend_generation BIGINT NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS history_lake.archive_invalidations ("
                    + "invalidation_id UUID NOT NULL, dataset VARCHAR NOT NULL, source_kind VARCHAR NOT NULL, "
                    + "range_start BIGINT NOT NULL, range_end BIGINT NOT NULL, invalidated_at TIMESTAMP NOT NULL)");
        }
    }

    private void createDatasetTables(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            for (ArchiveTableSchema table : DuckLakeSql.tables().values()) {
                sql.execute(DuckLakeSql.createTable(table));
            }
        }
    }

    private void configureTables(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CALL history_lake.set_option('data_inlining_row_limit', 0)");
            sql.execute("CALL history_lake.set_option('parquet_compression', 'zstd')");
            sql.execute("CALL history_lake.set_option('target_file_size', '" + config.targetFileSizeBytes() + "B')");
            sql.execute("CALL history_lake.set_option('parquet_row_group_size', '" + config.rowGroupSize() + "')");
            for (ArchiveTableSchema table : DuckLakeSql.tables().values()) {
                if (table.columns().stream().anyMatch(column -> column.name().equals("epoch"))) {
                    try {
                        sql.execute("ALTER TABLE history_lake." + DuckLakeSql.name(table.physicalName())
                                + " SET PARTITIONED BY (epoch)");
                    } catch (SQLException e) {
                        if (!isAlreadyPartitioned(e)) throw e;
                    }
                }
            }
        }
    }

    private void createViews(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE VIEW IF NOT EXISTS history_lake.transactions AS SELECT * FROM history_lake.chain_transaction");
            sql.execute("CREATE VIEW IF NOT EXISTS history_lake.transaction_output_amounts AS "
                    + "SELECT tx_hash, output_index, ''::BLOB AS policy_id, ''::BLOB AS asset_name, "
                    + "lovelace::DECIMAL(38,0) AS quantity, true AS is_lovelace FROM history_lake.transaction_outputs "
                    + "UNION ALL SELECT tx_hash, output_index, policy_id, asset_name, quantity, false "
                    + "FROM history_lake.transaction_output_assets");
            sql.execute("CREATE VIEW IF NOT EXISTS history_lake.output_lifecycle AS "
                    + "SELECT o.*, i.spending_tx_hash, i.block_number AS spent_block_number, i.slot AS spent_slot "
                    + "FROM history_lake.transaction_outputs o LEFT JOIN history_lake.transaction_inputs i "
                    + "ON i.referenced_tx_hash=o.tx_hash AND i.referenced_output_index=o.output_index AND i.consumes_output");
            sql.execute("CREATE VIEW IF NOT EXISTS history_lake.unspent_outputs AS "
                    + "SELECT * FROM history_lake.output_lifecycle WHERE spending_tx_hash IS NULL");
            sql.execute("CREATE VIEW IF NOT EXISTS history_lake.address_utxo_amounts AS "
                    + "SELECT o.address_key, o.tx_hash, o.output_index, a.policy_id, a.asset_name, a.quantity, a.is_lovelace, "
                    + "o.block_number, o.slot, o.epoch FROM history_lake.transaction_outputs o "
                    + "JOIN history_lake.transaction_output_amounts a USING (tx_hash, output_index)");
            sql.execute("CREATE VIEW IF NOT EXISTS history_lake.address_asset_flow AS "
                    + "SELECT address_key, tx_hash, output_index, policy_id, asset_name, quantity, is_lovelace, "
                    + "block_number, slot, epoch, 'received'::VARCHAR AS direction "
                    + "FROM history_lake.address_utxo_amounts UNION ALL "
                    + "SELECT o.address_key, i.spending_tx_hash, i.referenced_output_index, "
                    + "a.policy_id, a.asset_name, a.quantity, a.is_lovelace, "
                    + "i.block_number, i.slot, i.epoch, 'spent'::VARCHAR AS direction "
                    + "FROM history_lake.transaction_inputs i "
                    + "JOIN history_lake.transaction_outputs o "
                    + "ON o.tx_hash=i.referenced_tx_hash AND o.output_index=i.referenced_output_index "
                    + "JOIN history_lake.transaction_output_amounts a "
                    + "ON a.tx_hash=o.tx_hash AND a.output_index=o.output_index "
                    + "WHERE i.consumes_output");
        }
    }

    private void verifyOrCreateIdentity(Connection connection, ArchiveIdentity expected) throws SQLException {
        try (Statement sql = connection.createStatement();
             ResultSet result = sql.executeQuery("SELECT archive_id, engine, schema_version, network_magic, genesis_hash "
                     + "FROM history_lake.archive_identity")) {
            if (result.next()) {
                ArchiveIdentity actual = new ArchiveIdentity(UUID.fromString(result.getString(1)), result.getString(2),
                        result.getInt(3), result.getInt(4), result.getString(5));
                if (!actual.equals(expected) || result.next()) {
                    throw new ArchiveStoreException("DuckLake archive identity mismatch: expected=" + expected
                            + ", actual=" + actual);
                }
                return;
            }
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history_lake.archive_identity VALUES (?, ?, ?, ?, ?, current_timestamp)")) {
            insert.setObject(1, expected.archiveId());
            insert.setString(2, expected.engine());
            insert.setInt(3, expected.schemaVersion());
            insert.setInt(4, expected.networkMagic());
            insert.setString(5, expected.genesisHash());
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history_lake.archive_schema VALUES (?, ?, current_timestamp)")) {
            for (var entry : ArchiveSchemas.all().entrySet()) {
                insert.setString(1, entry.getKey().logicalName());
                insert.setInt(2, entry.getValue().projectionVersion());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private boolean isAlreadyPartitioned(SQLException error) {
        String message = error.getMessage();
        return message != null && (message.contains("already partitioned") || message.contains("same partition"));
    }
}
