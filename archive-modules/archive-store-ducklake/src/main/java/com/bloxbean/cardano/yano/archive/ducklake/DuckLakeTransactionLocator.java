package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import org.sqlite.JDBC;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rebuildable SQLite accelerator for uniformly distributed transaction hashes.
 *
 * <p><b>The locator is strictly a hint.</b> {@code findTransaction} issues an
 * authoritative full-range query against the pinned DuckLake snapshot whenever the
 * hint is absent, and falls back to the same query when a hinted block turns out
 * not to hold the row. Correctness therefore never depends on the locator being
 * current, which is what allows the cheap generation handling below.
 *
 * <p>ADR-038: mainnet measurement showed every archive commit spending ~340 s here
 * — 99.9% of write-session time — because {@code advance} demanded the locator sit
 * at exactly {@code generation - 1} and otherwise performed a full
 * {@code chain_transaction} rebuild. DuckLake generations also advance for
 * maintenance snapshots and for other datasets' commits, so with maintenance
 * running every 300 s and commits taking ~340 s a gap was almost always present,
 * and nearly every commit rebuilt.
 */
final class DuckLakeTransactionLocator implements AutoCloseable {
    record Entry(byte[] txHash, long blockNumber, UUID jobId) { }

    private static final System.Logger LOG = System.getLogger(DuckLakeTransactionLocator.class.getName());

    private final String jdbcUrl;
    private final Driver driver;
    private final AtomicLong fullRebuilds = new AtomicLong();

    DuckLakeTransactionLocator(Path catalogPath) {
        this(catalogPath, loadDriver());
    }

    DuckLakeTransactionLocator(Path catalogPath, Driver driver) {
        Path path = catalogPath.resolveSibling(catalogPath.getFileName() + ".tx-locator.sqlite");
        jdbcUrl = "jdbc:sqlite:" + path.toAbsolutePath().normalize();
        this.driver = Objects.requireNonNull(driver, "driver");
        try (Connection connection = open(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS tx_locator(tx_hash BLOB PRIMARY KEY, block_number INTEGER NOT NULL, job_id TEXT NOT NULL)");
            sql.execute("CREATE INDEX IF NOT EXISTS idx_tx_locator_job ON tx_locator(job_id)");
            sql.execute("CREATE TABLE IF NOT EXISTS locator_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1), generation INTEGER NOT NULL)");
            sql.execute("INSERT OR IGNORE INTO locator_meta VALUES(1,-1)");
        } catch (SQLException e) { throw new ArchiveStoreException("cannot initialize transaction locator", e); }
    }

    /** Number of full {@code chain_transaction} rebuilds performed by this instance. */
    long fullRebuilds() {
        return fullRebuilds.get();
    }

    long currentGeneration() {
        try (Connection connection = open()) {
            return generation(connection);
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator generation read failed", e); }
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

    /**
     * Hint lookup for a read pinned at {@code pinnedGeneration}.
     *
     * <p><b>Never rebuilds or rewinds.</b> The previous implementation called
     * {@code rebuildIfRequired(duckLake, pinnedGeneration)}, so a lookup through an
     * older pinned session rebuilt the single global locator *backward* to that
     * generation; the next writer then found an unexpected generation and rebuilt
     * forward again, letting concurrent reads and writes oscillate the locator and
     * trigger repeated multi-minute rebuilds. Reads are now pure.
     *
     * <p>A hint that is stale in either direction is safe: an absent hint makes the
     * caller issue an authoritative full-range query, and a hinted block that does
     * not hold the row falls back to the same query.
     */
    synchronized OptionalLong block(Connection duckLake, long pinnedGeneration, byte[] txHash) {
        return block(txHash);
    }

    /**
     * Applies a commit's entries and moves the locator to {@code generation}.
     *
     * <p>A **forward** generation gap is expected and is handled in O(entries):
     * DuckLake generations advance for maintenance snapshots and for commits by
     * other datasets, neither of which changes {@code chain_transaction}, and every
     * TRANSACTION commit applies its own entries through this method, so a gap
     * cannot skip entries. A **backward** move is unexplained and still triggers a
     * fail-safe rebuild, as does an uninitialised locator.
     */
    synchronized void advance(Connection duckLake, long generation, Collection<Entry> entries) {
        try (Connection connection = open()) {
            long current = generation(connection);
            if (current < 0) {
                rebuild(duckLake, generation, "uninitialised locator");
                return;
            }
            if (current > generation) {
                rebuild(duckLake, generation, "writer-side generation rewind from " + current);
                return;
            }
            connection.setAutoCommit(false);
            if (!entries.isEmpty()) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO tx_locator VALUES(?,?,?) ON CONFLICT(tx_hash) DO UPDATE SET block_number=excluded.block_number,job_id=excluded.job_id")) {
                    for (Entry entry : entries) {
                        insert.setBytes(1, entry.txHash()); insert.setLong(2, entry.blockNumber());
                        insert.setString(3, entry.jobId().toString()); insert.addBatch();
                    }
                    insert.executeBatch();
                }
            }
            setGeneration(connection, generation); connection.commit();
            if (current != generation - 1) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Locator advanced across generation gap {0}->{1} with {2} entries (no rebuild)",
                        current, generation, entries.size());
            }
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

    /** Startup recovery only; the read path must never call this. */
    synchronized void rebuildIfRequired(Connection duckLake, long generation) {
        long current;
        try (Connection locator = open()) {
            current = generation(locator);
            if (current == generation) return;
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator generation read failed", e); }
        rebuild(duckLake, generation, "startup recovery from generation " + current);
    }

    synchronized void rebuild(Connection duckLake, long generation) {
        rebuild(duckLake, generation, "unspecified");
    }

    synchronized void rebuild(Connection duckLake, long generation, String reason) {
        long started = System.nanoTime();
        long rows = 0;
        long previous = -1;
        try (Connection locator = open()) {
            previous = generation(locator);
            locator.setAutoCommit(false);
            try (Statement clear = locator.createStatement()) { clear.executeUpdate("DELETE FROM tx_locator"); }
            try (Statement scan = duckLake.createStatement();
                 ResultSet result = scan.executeQuery("SELECT tx_hash,block_number,archive_job_id FROM history_lake.chain_transaction");
                 PreparedStatement insert = locator.prepareStatement("INSERT INTO tx_locator VALUES(?,?,?)")) {
                int pending = 0;
                while (result.next()) {
                    insert.setBytes(1, result.getBytes(1)); insert.setLong(2, result.getLong(2));
                    insert.setString(3, result.getObject(3).toString()); insert.addBatch();
                    rows++;
                    if (++pending == 10_000) { insert.executeBatch(); pending = 0; }
                }
                if (pending > 0) insert.executeBatch();
            }
            setGeneration(locator, generation); locator.commit();
        } catch (SQLException e) { throw new ArchiveStoreException("transaction locator rebuild failed", e); }
        long count = fullRebuilds.incrementAndGet();
        LOG.log(System.Logger.Level.INFO,
                "Locator full rebuild #{0}: {1}->{2}, reason={3}, rows={4}, {5}s",
                count, previous, generation, reason, rows,
                String.format("%.3f", (System.nanoTime() - started) / 1e9));
    }

    private Connection open() throws SQLException {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            long registeredDriverCount = DriverManager.drivers().count();
            LOG.log(System.Logger.Level.DEBUG,
                    "Opening SQLite locator connection through explicit JDBC.connect; "
                            + "DriverManager registered-driver count={0}",
                    registeredDriverCount);
        }
        Connection connection = driver.connect(jdbcUrl, new Properties());
        if (connection == null) {
            throw new SQLException("SQLite driver declined JDBC URL: " + jdbcUrl);
        }
        try (Statement sql = connection.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA synchronous=FULL");
            sql.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static JDBC loadDriver() {
        try {
            return new JDBC();
        } catch (RuntimeException | LinkageError e) {
            throw new ArchiveStoreException("SQLite locator driver unavailable", e);
        }
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
