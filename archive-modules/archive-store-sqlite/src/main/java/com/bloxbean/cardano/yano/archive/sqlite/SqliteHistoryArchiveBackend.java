package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveCapabilities;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveHealth;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveMaintenanceBudget;
import com.bloxbean.cardano.yano.archive.api.ArchiveRange;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRetentionCutoff;
import com.bloxbean.cardano.yano.archive.api.ArchiveRepositorySet;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWriteSession;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.EpochRange;
import com.bloxbean.cardano.yano.archive.api.SourceKind;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.internal.JdbcArchiveRepositorySet;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone SQLite archive. It never loads DuckDB or writes Parquet. */
public final class SqliteHistoryArchiveBackend implements ArchiveBackend {
    private static final System.Logger LOG = System.getLogger(SqliteHistoryArchiveBackend.class.getName());
    private static final String MAINNET_SHELLEY_GENESIS_HASH =
            "1a3be38bcbb7911969283716ad7aa550250226b76a61fc51cc9a9a35d9276d81";
    private static final ArchiveCapabilities CAPABILITIES = new ArchiveCapabilities(
            true, true, true, false, true);

    private final ArchiveIdentity identity;
    private final SqliteArchiveConfig config;
    private final SqliteArchiveFileLock fileLock;
    private final Semaphore writer = new Semaphore(1, true);
    private final Semaphore readers;
    private final AtomicReference<ArchiveHealth> health = new AtomicReference<>(ArchiveHealth.healthy());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean fileLockClosed = new AtomicBoolean();
    private final ArchiveRepositorySet repositories = new JdbcArchiveRepositorySet(
            session -> {
                if (!(session instanceof SqliteReadSession sqlite)) {
                    throw new IllegalArgumentException("read session does not belong to SQLite backend");
                }
                return sqlite.connection();
            }, "", "archive_coverage");

    public SqliteHistoryArchiveBackend(ArchiveIdentity identity, SqliteArchiveConfig config) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        if (!identity.engine().equals("sqlite")) throw new IllegalArgumentException("SQLite backend requires engine=sqlite");
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.readers = new Semaphore(config.maxReaders(), true);
        this.fileLock = new SqliteArchiveFileLock(config.databasePath());
        try {
            new SqliteArchiveInitializer(config).initialize(identity);
            if (MAINNET_SHELLEY_GENESIS_HASH.equals(identity.genesisHash())) {
                LOG.log(System.Logger.Level.WARNING,
                        "Standalone SQLite archive selected for Cardano mainnet; DuckLake is the large-history default");
            }
        } catch (Exception e) {
            fileLock.close();
            markUnhealthy("SQLite initialization failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite initialization failed", e);
        }
    }

    @Override
    public ArchiveIdentity identity() { return identity; }

    @Override
    public ArchiveCapabilities capabilities() { return CAPABILITIES; }

    @Override
    public ArchiveWriteSession begin(ArchiveJob job) {
        requireOpen();
        java.util.Objects.requireNonNull(job, "job");
        if (!job.networkIdentity().equals(identity.networkIdentity())) {
            throw new ArchiveStoreException("archive job network identity does not match backend");
        }
        acquireWriter();
        Connection connection = null;
        try {
            connection = SqliteArchiveSql.open(config, false);
            Optional<ArchiveReceipt> replay = findReceipt(connection, job.jobId());
            if (replay.isPresent()) {
                verifyReplayMetadata(job, replay.orElseThrow());
                connection.close();
                return new SqliteWriteSession(this, job, null, replay.orElseThrow(), replay.orElseThrow().backendGeneration());
            }
            ((SQLiteConnection) connection).setCurrentTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            connection.setAutoCommit(false);
            rejectCoverageOverlap(connection, job);
            long generation = Math.addExact(currentGeneration(connection), 1);
            insertJobHeader(connection, job, generation);
            return new SqliteWriteSession(this, job, connection, null, generation);
        } catch (Exception e) {
            if (connection != null) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                try { connection.close(); } catch (SQLException ignored) { }
            }
            releaseWriter();
            markDegraded("SQLite begin failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("failed to begin SQLite job", e);
        }
    }

    @Override
    public Optional<ArchiveReceipt> findReceipt(UUID jobId) {
        requireOpen();
        acquireReader();
        try (Connection connection = SqliteArchiveSql.open(config, true)) {
            connection.setAutoCommit(false);
            return findReceipt(connection, jobId);
        } catch (SQLException e) {
            markDegraded("SQLite receipt read failed", e);
            throw new ArchiveStoreException("failed to read SQLite receipt", e);
        } finally {
            releaseReaderPermit();
        }
    }

    @Override
    public ArchiveCoverage coverage(ArchiveDatasetId dataset) {
        requireOpen();
        acquireReader();
        try (Connection connection = SqliteArchiveSql.open(config, true);
             PreparedStatement query = connection.prepareStatement(
                     "SELECT projection_version, source_kind, range_start, range_end "
                             + "FROM archive_coverage WHERE dataset=? ORDER BY range_start")) {
            connection.setAutoCommit(false);
            long revision = currentGeneration(connection); // pins metadata and coverage to one WAL snapshot
            query.setQueryTimeout(timeoutSeconds(config.queryTimeout()));
            query.setString(1, dataset.name());
            List<ArchiveRange> ranges = new ArrayList<>();
            int projectionVersion = ArchiveSchemas.schema(dataset).projectionVersion();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    projectionVersion = rows.getInt(1);
                    SourceKind kind = SourceKind.valueOf(rows.getString(2));
                    ranges.add(kind == SourceKind.BLOCK
                            ? new BlockRange(rows.getLong(3), rows.getLong(4))
                            : new EpochRange(rows.getLong(3), rows.getLong(4)));
                }
            }
            return new ArchiveCoverage(dataset, projectionVersion, revision, mergeAdjacent(ranges));
        } catch (SQLException e) {
            markDegraded("SQLite coverage read failed", e);
            throw new ArchiveStoreException("failed to read SQLite coverage", e);
        } finally {
            releaseReaderPermit();
        }
    }

    @Override
    public ArchiveReadSession openReadSession() {
        requireOpen();
        acquireReader();
        Connection connection = null;
        try {
            connection = SqliteArchiveSql.open(config, true);
            connection.setAutoCommit(false);
            long generation = currentGeneration(connection); // establishes the WAL snapshot
            return new SqliteReadSession(this, generation, connection);
        } catch (Exception e) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            readers.release();
            markDegraded("SQLite snapshot open failed", e);
            throw new ArchiveStoreException("failed to open SQLite read snapshot", e);
        }
    }

    @Override
    public ArchiveRepositorySet repositories() {
        return repositories;
    }

    @Override
    public void invalidate(ArchiveDatasetId dataset, ArchiveRange range) {
        mutateCommittedJobs(dataset, range, false);
    }

    @Override
    public void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff) {
        if (dataset.sourceKind() != cutoff.sourceKind()) throw new IllegalArgumentException("cutoff source mismatch");
        if (cutoff.beforeExclusive() == 0) return;
        ArchiveRange range = cutoff.sourceKind() == SourceKind.BLOCK
                ? new BlockRange(0, cutoff.beforeExclusive() - 1)
                : new EpochRange(0, cutoff.beforeExclusive() - 1);
        mutateCommittedJobs(dataset, range, true);
    }

    @Override
    public void maintain(ArchiveMaintenanceBudget budget) {
        requireOpen();
        java.util.Objects.requireNonNull(budget, "budget");
        acquireWriter();
        long deadline = System.nanoTime() + budget.timeLimit().toNanos();
        boolean exclusiveReaders = false;
        try (Connection connection = SqliteArchiveSql.open(config, false)) {
            executeBounded(connection, deadline, "PRAGMA wal_checkpoint(PASSIVE)");
            executeBounded(connection, deadline, "PRAGMA optimize");
            long databaseBytes = Files.size(config.databasePath());
            long freePages = SqliteArchiveSql.scalarLong(connection, "PRAGMA freelist_count");
            if (freePages > 0 && budget.maxBytesToRewrite() >= databaseBytes && databaseBytes > 0) {
                exclusiveReaders = readers.tryAcquire(config.maxReaders());
                if (exclusiveReaders) executeBounded(connection, deadline, "VACUUM");
            }
            health.set(ArchiveHealth.healthy());
        } catch (Exception e) {
            markDegraded("SQLite maintenance failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite maintenance failed", e);
        } finally {
            if (exclusiveReaders) readers.release(config.maxReaders());
            releaseWriter();
        }
    }

    @Override
    public ArchiveHealth health() { return health.get(); }

    public SqliteStorageStats storageStats() {
        requireOpen();
        acquireReader();
        try (Connection connection = SqliteArchiveSql.open(config, true)) {
            long pageCount = SqliteArchiveSql.scalarLong(connection, "PRAGMA page_count");
            long freePages = SqliteArchiveSql.scalarLong(connection, "PRAGMA freelist_count");
            long pageSize = SqliteArchiveSql.scalarLong(connection, "PRAGMA page_size");
            Path wal = Path.of(config.databasePath() + "-wal");
            long walBytes = Files.exists(wal) ? Files.size(wal) : 0;
            return new SqliteStorageStats(pageCount, freePages, pageSize,
                    Math.multiplyExact(pageCount, pageSize), Math.multiplyExact(freePages, pageSize), walBytes);
        } catch (Exception e) {
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("failed to read SQLite storage statistics", e);
        } finally {
            releaseReaderPermit();
        }
    }

    public void verifyIntegrity(Duration timeout) {
        requireOpen();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("integrity timeout must be positive");
        }
        acquireReader();
        try (Connection connection = SqliteArchiveSql.open(config, true);
             Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            sql.setQueryTimeout(timeoutSeconds(timeout));
            try (ResultSet result = sql.executeQuery("PRAGMA integrity_check")) {
                if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1)) || result.next()) {
                    throw new ArchiveStoreException("SQLite integrity_check failed");
                }
            }
            try (ResultSet result = sql.executeQuery("SELECT count(*) FROM archive_coverage c "
                    + "LEFT JOIN archive_commits j ON c.job_id=j.job_id WHERE j.job_id IS NULL")) {
                result.next();
                if (result.getLong(1) != 0) throw new ArchiveStoreException("SQLite coverage has orphan receipts");
            }
            health.set(ArchiveHealth.healthy());
        } catch (Exception e) {
            markUnhealthy("SQLite integrity check failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite integrity check failed", e);
        } finally {
            readers.release();
        }
    }

    public Path backup(Path target) {
        requireOpen();
        Path normalized = java.util.Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (normalized.equals(config.databasePath())) throw new IllegalArgumentException("backup target equals database");
        acquireWriter();
        try {
            Path parent = normalized.getParent();
            if (parent == null) throw new ArchiveStoreException("backup target has no parent");
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, normalized.getFileName().toString(), ".tmp");
            try (Connection connection = SqliteArchiveSql.open(config, true)) {
                int result = ((SQLiteConnection) connection).getDatabase()
                        .backup("main", temporary.toString(), null, 100, 10, timeoutMillis(config.queryTimeout()));
                if (result != 0) throw new ArchiveStoreException("SQLite online backup returned code " + result);
                try {
                    Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return normalized;
        } catch (Exception e) {
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite online backup failed", e);
        } finally {
            releaseWriter();
        }
    }

    void releaseWriter() {
        writer.release();
        finishCloseIfIdle();
    }

    void releaseReader() {
        releaseReaderPermit();
    }

    private void mutateCommittedJobs(ArchiveDatasetId dataset, ArchiveRange range, boolean wholeJobsOnly) {
        requireOpen();
        if (dataset.sourceKind() != range.sourceKind()) throw new IllegalArgumentException("dataset/range source mismatch");
        acquireWriter();
        try (Connection connection = SqliteArchiveSql.open(config, false)) {
            ((SQLiteConnection) connection).setCurrentTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            connection.setAutoCommit(false);
            try {
                List<String> jobs = findAffectedJobs(connection, dataset, range, wholeJobsOnly);
                if (wholeJobsOnly && jobs.isEmpty()) {
                    connection.rollback();
                    return;
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM archive_commits WHERE job_id=?")) {
                    for (String job : jobs) {
                        delete.setString(1, job);
                        delete.executeUpdate();
                    }
                }
                if (dataset == ArchiveDatasetId.UTXO_HISTORY && !jobs.isEmpty()) {
                    reconcileAddressDimension(connection);
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO archive_invalidations VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, dataset.name());
                    insert.setString(3, range.sourceKind().name());
                    insert.setLong(4, range.startInclusive());
                    insert.setLong(5, range.endInclusive());
                    insert.setLong(6, Instant.now().toEpochMilli());
                    insert.executeUpdate();
                }
                incrementGeneration(connection);
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            markDegraded("SQLite invalidation failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite invalidation failed", e);
        } finally {
            releaseWriter();
        }
    }

    private void reconcileAddressDimension(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.executeUpdate("DELETE FROM addresses WHERE NOT EXISTS (SELECT 1 FROM transaction_outputs o "
                    + "WHERE o.address_key=addresses.address_key)");
            sql.executeUpdate("UPDATE addresses SET (first_seen_block_number, first_seen_slot, first_seen_epoch)="
                    + "(SELECT o.block_number, o.slot, o.epoch FROM transaction_outputs o "
                    + "WHERE o.address_key=addresses.address_key ORDER BY o.block_number, o.tx_index, o.output_index LIMIT 1)");
        }
    }

    private List<String> findAffectedJobs(Connection connection, ArchiveDatasetId dataset,
                                          ArchiveRange range, boolean wholeJobsOnly) throws SQLException {
        String predicate = wholeJobsOnly ? "range_end <= ?" : "NOT (range_end < ? OR range_start > ?)";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT job_id FROM archive_coverage WHERE dataset=? AND " + predicate)) {
            query.setString(1, dataset.name());
            if (wholeJobsOnly) query.setLong(2, range.endInclusive());
            else {
                query.setLong(2, range.startInclusive());
                query.setLong(3, range.endInclusive());
            }
            List<String> jobs = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) jobs.add(rows.getString(1));
            }
            return jobs;
        }
    }

    private void insertJobHeader(Connection connection, ArchiveJob job, long generation) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO archive_commits VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', 0)")) {
            insert.setString(1, job.jobId().toString());
            insert.setString(2, job.dataset().name());
            insert.setInt(3, job.projectionVersion());
            insert.setString(4, job.range().sourceKind().name());
            insert.setLong(5, job.range().startInclusive());
            insert.setLong(6, job.range().endInclusive());
            insert.setLong(7, job.anchors().startSlot());
            insert.setBytes(8, job.anchors().startHash());
            insert.setLong(9, job.anchors().endSlot());
            insert.setBytes(10, job.anchors().endHash());
            insert.setString(11, job.sourceStateVersion());
            insert.setLong(12, generation);
            insert.executeUpdate();
        }
    }

    private void rejectCoverageOverlap(Connection connection, ArchiveJob job) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM archive_coverage WHERE dataset=? "
                        + "AND NOT (range_end < ? OR range_start > ?)")) {
            query.setString(1, job.dataset().name());
            query.setLong(2, job.range().startInclusive());
            query.setLong(3, job.range().endInclusive());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                if (result.getLong(1) != 0) {
                    throw new ArchiveStoreException("archive job overlaps committed coverage for " + job.dataset());
                }
            }
        }
    }

    private Optional<ArchiveReceipt> findReceipt(Connection connection, UUID jobId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT dataset, projection_version, source_kind, range_start, range_end, start_slot, start_hash, "
                        + "end_slot, end_hash, backend_generation, ordered_digest, committed_at "
                        + "FROM archive_commits WHERE job_id=?")) {
            query.setString(1, jobId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                SourceKind kind = SourceKind.valueOf(row.getString(3));
                ArchiveRange range = kind == SourceKind.BLOCK
                        ? new BlockRange(row.getLong(4), row.getLong(5))
                        : new EpochRange(row.getLong(4), row.getLong(5));
                ArchiveRangeAnchor anchors = new ArchiveRangeAnchor(row.getLong(6), row.getBytes(7),
                        row.getLong(8), row.getBytes(9));
                ArchiveReceipt receipt = new ArchiveReceipt(jobId, identity.networkIdentity(),
                        ArchiveDatasetId.valueOf(row.getString(1)), row.getInt(2), range, anchors,
                        row.getLong(10), readCounts(connection, jobId), row.getString(11),
                        Instant.ofEpochMilli(row.getLong(12)));
                if (row.next()) throw new ArchiveStoreException("duplicate SQLite receipt for " + jobId);
                return Optional.of(receipt);
            }
        }
    }

    private Map<String, Long> readCounts(Connection connection, UUID jobId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT table_name, row_count FROM archive_commit_counts WHERE job_id=?")) {
            query.setString(1, jobId.toString());
            Map<String, Long> counts = new LinkedHashMap<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) counts.put(rows.getString(1), rows.getLong(2));
            }
            return counts;
        }
    }

    private void verifyReplayMetadata(ArchiveJob job, ArchiveReceipt receipt) {
        if (receipt.dataset() != job.dataset() || receipt.projectionVersion() != job.projectionVersion()
                || !receipt.range().equals(job.range()) || !receipt.anchors().equals(job.anchors())
                || !receipt.networkIdentity().equals(job.networkIdentity())) {
            throw new ArchiveStoreException("job ID conflicts with different committed metadata: " + job.jobId());
        }
    }

    private long currentGeneration(Connection connection) throws SQLException {
        return SqliteArchiveSql.scalarLong(connection,
                "SELECT generation FROM archive_generation WHERE singleton=1");
    }

    private void incrementGeneration(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            if (sql.executeUpdate("UPDATE archive_generation SET generation=generation+1 WHERE singleton=1") != 1) {
                throw new ArchiveStoreException("missing SQLite archive generation");
            }
        }
    }

    private List<ArchiveRange> mergeAdjacent(List<ArchiveRange> ranges) {
        if (ranges.isEmpty()) return List.of();
        List<ArchiveRange> merged = new ArrayList<>();
        ArchiveRange current = ranges.getFirst();
        for (int index = 1; index < ranges.size(); index++) {
            ArchiveRange next = ranges.get(index);
            if (current.sourceKind() == next.sourceKind() && current.endInclusive() != Long.MAX_VALUE
                    && current.endInclusive() + 1 == next.startInclusive()) {
                current = current.sourceKind() == SourceKind.BLOCK
                        ? new BlockRange(current.startInclusive(), next.endInclusive())
                        : new EpochRange(current.startInclusive(), next.endInclusive());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    private void executeBounded(Connection connection, long deadline, String command) throws SQLException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new ArchiveStoreException("SQLite operation exceeded its time budget");
        try (Statement sql = connection.createStatement()) {
            sql.setQueryTimeout(timeoutSeconds(Duration.ofNanos(remaining)));
            sql.execute(command);
        }
    }

    private void acquireWriter() {
        try {
            if (!writer.tryAcquire(config.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ArchiveStoreException("timed out waiting for SQLite writer");
            }
            if (closed.get()) {
                writer.release();
                throw new IllegalStateException("SQLite backend is closed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchiveStoreException("interrupted waiting for SQLite writer", e);
        }
    }

    private void acquireReader() {
        try {
            if (!readers.tryAcquire(config.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ArchiveStoreException("timed out waiting for SQLite archive reader");
            }
            if (closed.get()) {
                readers.release();
                throw new IllegalStateException("SQLite backend is closed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchiveStoreException("interrupted waiting for SQLite archive reader", e);
        }
    }

    private int timeoutSeconds(Duration timeout) {
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toSeconds()
                + (timeout.toNanosPart() == 0 ? 0 : 1))));
    }

    private int timeoutMillis(Duration timeout) {
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis())));
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("SQLite backend is closed");
    }

    private void markDegraded(String detail, Throwable error) {
        health.set(new ArchiveHealth(ArchiveHealth.Status.DEGRADED,
                detail + ": " + error.getMessage(), Instant.now()));
    }

    private void markUnhealthy(String detail, Throwable error) {
        health.set(new ArchiveHealth(ArchiveHealth.Status.UNHEALTHY,
                detail + ": " + error.getMessage(), Instant.now()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        health.set(new ArchiveHealth(ArchiveHealth.Status.CLOSED, "", Instant.now()));
        finishCloseIfIdle();
    }

    private void releaseReaderPermit() {
        readers.release();
        finishCloseIfIdle();
    }

    private void finishCloseIfIdle() {
        if (closed.get() && writer.availablePermits() == 1
                && readers.availablePermits() == config.maxReaders()
                && fileLockClosed.compareAndSet(false, true)) {
            fileLock.close();
        }
    }
}
