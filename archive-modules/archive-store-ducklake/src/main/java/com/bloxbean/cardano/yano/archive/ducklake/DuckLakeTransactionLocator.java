package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Rebuildable SQLite accelerator for uniformly distributed transaction hashes. */
final class DuckLakeTransactionLocator implements AutoCloseable {
    record Entry(byte[] txHash, long blockNumber, UUID jobId) { }
    private final String jdbcUrl;

    DuckLakeTransactionLocator(Path catalogPath) {
        Path path = catalogPath.resolveSibling(catalogPath.getFileName() + ".tx-locator.sqlite");
        jdbcUrl = "jdbc:sqlite:" + path.toAbsolutePath().normalize();
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new ArchiveStoreException("SQLite locator driver unavailable", e); }
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS tx_locator(tx_hash BLOB PRIMARY KEY, block_number INTEGER NOT NULL, job_id TEXT NOT NULL)");
            sql.execute("CREATE INDEX IF NOT EXISTS idx_tx_locator_job ON tx_locator(job_id)");
            sql.execute("CREATE TABLE IF NOT EXISTS locator_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL)");
            sql.execute("INSERT OR IGNORE INTO locator_meta VALUES(1,-1)");
        } catch (SQLException e) { throw new ArchiveStoreException("cannot initialize transaction locator", e); }
    }

    synchronized OptionalLong block(byte[] txHash) {
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement(
                "SELECT block_number FROM tx_locator WHERE tx_hash=?")) {
            query.setBytes(1, txHash);
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? OptionalLong.of(row.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator query failed", e); }
    }

    /** Rebuild and lookup are one critical section so concurrent pinned generations cannot cross-contaminate. */
    synchronized OptionalLong block(Connection duckLake, long generation, byte[] txHash) {
        rebuildIfRequired(duckLake, generation);
        return block(txHash);
    }

    synchronized void advance(Connection duckLake, long generation, Collection<Entry> entries) {
        try (Connection connection = open()) {
            if (generation(connection) != generation - 1) {
                rebuild(duckLake, generation);
                return;
            }
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tx_locator VALUES(?,?,?) ON CONFLICT(tx_hash) DO UPDATE SET block_number=excluded.block_number,job_id=excluded.job_id")) {
                for (Entry entry : entries) {
                    insert.setBytes(1, entry.txHash()); insert.setLong(2, entry.blockNumber());
                    insert.setString(3, entry.jobId().toString()); insert.addBatch();
                }
                insert.executeBatch();
            }
            setGeneration(connection, generation); connection.commit();
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator update failed", e); }
    }

    synchronized void removeJobs(long generation, Collection<UUID> jobs) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM tx_locator WHERE job_id=?")) {
                for (UUID job : jobs) { delete.setString(1, job.toString()); delete.addBatch(); }
                delete.executeBatch();
            }
            setGeneration(connection, generation); connection.commit();
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator invalidation failed", e); }
    }

    synchronized void rebuildIfRequired(Connection duckLake, long generation) {
        try (Connection locator = open()) {
            if (generation(locator) == generation) return;
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator generation read failed", e); }
        rebuild(duckLake, generation);
    }

    synchronized void rebuild(Connection duckLake, long generation) {
        try (Connection locator = open()) {
            locator.setAutoCommit(false);
            try (Statement clear = locator.createStatement()) { clear.executeUpdate("DELETE FROM tx_locator"); }
            try (Statement scan = duckLake.createStatement();
                 ResultSet rows = scan.executeQuery("SELECT tx_hash,block_number,archive_job_id FROM history_lake.chain_transaction");
                 PreparedStatement insert = locator.prepareStatement("INSERT INTO tx_locator VALUES(?,?,?)")) {
                int pending = 0;
                while (rows.next()) {
                    insert.setBytes(1, rows.getBytes(1)); insert.setLong(2, rows.getLong(2));
                    insert.setString(3, rows.getObject(3).toString()); insert.addBatch();
                    if (++pending == 10_000) { insert.executeBatch(); pending = 0; }
                }
                if (pending > 0) insert.executeBatch();
            }
            setGeneration(locator, generation); locator.commit();
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator rebuild failed", e); }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA synchronous=FULL");
            sql.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
    private long generation(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet row = sql.executeQuery(
                "SELECT generation FROM locator_meta WHERE singleton=1")) { row.next(); return row.getLong(1); }
    }
    private void setGeneration(Connection connection, long value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE locator_meta SET generation=? WHERE singleton=1")) { update.setLong(1, value); update.executeUpdate(); }
    }
    public void close() { }
}
