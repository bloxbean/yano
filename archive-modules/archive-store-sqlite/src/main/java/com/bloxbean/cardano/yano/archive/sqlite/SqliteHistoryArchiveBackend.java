package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveCapabilities;
import com.bloxbean.cardano.yano.archive.api.ArchiveCommitBoundary;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveFatalException;
import com.bloxbean.cardano.yano.archive.api.ArchiveHealth;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveMaintenanceBudget;
import com.bloxbean.cardano.yano.archive.api.ArchiveRange;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveResourceDiagnostics;
import com.bloxbean.cardano.yano.archive.api.ArchiveResourceGate;
import com.bloxbean.cardano.yano.archive.api.ArchiveStuckOperationException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;
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
import java.util.OptionalLong;
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
    // Fair semaphores stay the FIFO mechanism; the gates add only wait
    // diagnostics and the warn-versus-stuck distinction.
    private final Semaphore writer = new Semaphore(1, true);
    private final Semaphore readers;
    private final ArchiveResourceGate writerGate;
    private final ArchiveResourceGate readerGate;
    private final AtomicReference<ArchiveResourceDiagnostics.FailureEvent> lastFailure =
            new AtomicReference<>();
    private final AtomicReference<String> lastMaintenanceDeferral = new AtomicReference<>();
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
        this.writerGate = new ArchiveResourceGate("sqlite-writer", 1, writer,
                config.waitPolicy(), SqliteHistoryArchiveBackend::logWait);
        this.readerGate = new ArchiveResourceGate("sqlite-readers", config.maxReaders(), readers,
                config.waitPolicy(), SqliteHistoryArchiveBackend::logWait);
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
            throw new ArchiveFatalException("archive job network identity does not match backend");
        }
        int currentProjection = ArchiveSchemas.schema(job.dataset()).projectionVersion();
        if (job.projectionVersion() != currentProjection) {
            throw new ArchiveFatalException("archive job projection version " + job.projectionVersion()
                    + " does not match current " + currentProjection + " for " + job.dataset());
        }
        long writerTicket = -1;
        Connection connection = null;
        try {
            // Inside the guarded scope so a stuck writer wait is recorded like
            // any other mutation failure rather than escaping undiagnosed.
            writerTicket = acquireWriter("begin " + job.dataset().name());
            connection = SqliteArchiveSql.open(config, false);
            Optional<ArchiveReceipt> replay = findReceipt(connection, job.jobId());
            if (replay.isPresent()) {
                verifyReplayMetadata(job, replay.orElseThrow());
                connection.close();
                return new SqliteWriteSession(this, job, null, replay.orElseThrow(),
                        replay.orElseThrow().backendGeneration(), writerTicket);
            }
            ((SQLiteConnection) connection).setCurrentTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            connection.setAutoCommit(false);
            rejectCoverageOverlap(connection, job);
            long generation = Math.addExact(currentGeneration(connection), 1);
            insertJobHeader(connection, job, generation);
            return new SqliteWriteSession(this, job, connection, null, generation, writerTicket);
        } catch (Exception e) {
            if (connection != null) {
                try { connection.rollback(); } catch (SQLException ignored) { }
                try { connection.close(); } catch (SQLException ignored) { }
            }
            releaseWriter(writerTicket);
            recordFailure("begin " + job.dataset().name(), e);
            markDegraded("SQLite begin failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("failed to begin SQLite job", e);
        }
    }

    @Override
    public Optional<ArchiveReceipt> findReceipt(UUID jobId) {
        requireOpen();
        long readerTicket = acquireReader("find-receipt");
        try (Connection connection = SqliteArchiveSql.open(config, true)) {
            connection.setAutoCommit(false);
            return findReceipt(connection, jobId);
        } catch (SQLException e) {
            markDegraded("SQLite receipt read failed", e);
            throw new ArchiveStoreException("failed to read SQLite receipt", e);
        } finally {
            releaseReaderPermit(readerTicket);
        }
    }

    @Override
    public ArchiveCoverage coverage(ArchiveDatasetId dataset) {
        requireOpen();
        long readerTicket = acquireReader("coverage " + dataset.name());
        try (Connection connection = SqliteArchiveSql.open(config, true)) {
            connection.setAutoCommit(false);
            long revision = currentGeneration(connection); // pins metadata and coverage to one WAL snapshot
            return coverage(connection, revision, dataset);
        } catch (SQLException e) {
            markDegraded("SQLite coverage read failed", e);
            throw new ArchiveStoreException("failed to read SQLite coverage", e);
        } finally {
            releaseReaderPermit(readerTicket);
        }
    }

    @Override
    public ArchiveCoverage coverage(ArchiveReadSession session, ArchiveDatasetId dataset) {
        requireOpen();
        if (!(session instanceof SqliteReadSession read)) {
            throw new IllegalArgumentException("read session does not belong to SQLite backend");
        }
        try {
            return coverage(read.connection(), read.generation(), dataset);
        } catch (SQLException e) {
            markDegraded("SQLite pinned coverage read failed", e);
            throw new ArchiveStoreException("failed to read pinned SQLite coverage", e);
        }
    }

    @Override
    public Optional<ArchiveCommitBoundary> latestBlockBoundary(
            ArchiveReadSession session, ArchiveDatasetId dataset,
            BlockRange range, OptionalLong atOrBeforeSlot) {
        requireOpen();
        if (!(session instanceof SqliteReadSession read)) {
            throw new IllegalArgumentException("read session does not belong to SQLite backend");
        }
        if (dataset.sourceKind() != SourceKind.BLOCK) {
            throw new IllegalArgumentException("block boundary requires a block dataset");
        }
        String slotPredicate = atOrBeforeSlot.isPresent() ? " AND c.end_slot<=?" : "";
        try (PreparedStatement query = read.connection().prepareStatement(
                "SELECT c.projection_version, c.range_start, c.range_end, c.start_slot, c.start_hash, "
                        + "c.end_slot, c.end_hash, c.backend_generation FROM archive_commits c "
                        + "JOIN archive_coverage v ON v.job_id=c.job_id "
                        + "WHERE c.dataset=? AND c.source_kind='BLOCK' AND c.range_end BETWEEN ? AND ?"
                        + slotPredicate + " ORDER BY c.range_end DESC LIMIT 1")) {
            query.setQueryTimeout(timeoutSeconds(config.queryTimeout()));
            query.setString(1, dataset.name());
            query.setLong(2, range.startInclusive());
            query.setLong(3, range.endInclusive());
            if (atOrBeforeSlot.isPresent()) query.setLong(4, atOrBeforeSlot.getAsLong());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ArchiveCommitBoundary(dataset, row.getInt(1),
                        new BlockRange(row.getLong(2), row.getLong(3)),
                        new ArchiveRangeAnchor(row.getLong(4), row.getBytes(5), row.getLong(6), row.getBytes(7)),
                        row.getLong(8)));
            }
        } catch (SQLException e) {
            markDegraded("SQLite block boundary read failed", e);
            throw new ArchiveStoreException("failed to read SQLite block boundary", e);
        }
    }

    @Override
    public ArchiveReadSession openReadSession() {
        requireOpen();
        // Request-facing: a query must fail within the bounded acquisition
        // timeout so the API can answer unavailable, rather than parking an HTTP
        // worker toward the five-minute stuck threshold and exhausting request
        // capacity. Internal worker reads keep the longer policy.
        long readerTicket = acquireReader("read-session",
                config.waitPolicy().boundedTo(config.acquireTimeout()));
        Connection connection = null;
        try {
            connection = SqliteArchiveSql.open(config, true);
            connection.setAutoCommit(false);
            long generation = currentGeneration(connection); // establishes the WAL snapshot
            return new SqliteReadSession(this, generation, connection, readerTicket);
        } catch (Exception e) {
            if (connection != null) try { connection.close(); } catch (SQLException ignored) { }
            releaseReaderPermit(readerTicket);
            markDegraded("SQLite snapshot open failed", e);
            throw new ArchiveStoreException("failed to open SQLite read snapshot", e);
        }
    }

    private ArchiveCoverage coverage(Connection connection, long revision, ArchiveDatasetId dataset)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT projection_version, source_kind, range_start, range_end "
                        + "FROM archive_coverage WHERE dataset=? ORDER BY range_start")) {
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
    public int invalidateEpochJobsAfterSlot(ArchiveDatasetId dataset, long rollbackSlot) {
        if (dataset.sourceKind() != SourceKind.EPOCH) {
            throw new IllegalArgumentException("boundary-slot invalidation requires an epoch dataset");
        }
        if (rollbackSlot < 0) throw new IllegalArgumentException("rollbackSlot must be non-negative");
        requireOpen();
        long writerTicket = -1;
        try (Connection connection = SqliteArchiveSql.open(config, false)) {
            writerTicket = acquireWriter("invalidate-epoch-jobs " + dataset.name());
            ((SQLiteConnection) connection).setCurrentTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            connection.setAutoCommit(false);
            try {
                List<String> jobs = new ArrayList<>();
                long firstEpoch = Long.MAX_VALUE;
                long lastEpoch = -1;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT job_id, range_start, range_end FROM archive_commits "
                                + "WHERE dataset=? AND source_kind='EPOCH' AND end_slot>?")) {
                    query.setString(1, dataset.name());
                    query.setLong(2, rollbackSlot);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            jobs.add(rows.getString(1));
                            firstEpoch = Math.min(firstEpoch, rows.getLong(2));
                            lastEpoch = Math.max(lastEpoch, rows.getLong(3));
                        }
                    }
                }
                if (jobs.isEmpty()) {
                    connection.rollback();
                    return 0;
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM archive_commits WHERE job_id=?")) {
                    for (String job : jobs) {
                        delete.setString(1, job);
                        delete.executeUpdate();
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO archive_invalidations VALUES (?, ?, 'EPOCH', ?, ?, ?)")) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, dataset.name());
                    insert.setLong(3, firstEpoch);
                    insert.setLong(4, lastEpoch);
                    insert.setLong(5, Instant.now().toEpochMilli());
                    insert.executeUpdate();
                }
                incrementGeneration(connection);
                connection.commit();
                return jobs.size();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            recordFailure("invalidate-epoch-jobs " + dataset.name(), e);
            markDegraded("SQLite epoch rollback invalidation failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite epoch rollback invalidation failed", e);
        } finally {
            releaseWriter(writerTicket);
        }
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
        boolean exclusiveReaders = false;
        // Acquired inside the protected scope with a sentinel ticket: acquiring
        // outside it meant a stuck writer bypassed the deferral bookkeeping and
        // operators saw a maintenance error with no reason.
        long writerTicket = -1;
        try {
            writerTicket = acquireWriter("maintenance");
            // The budget covers execution, not the wait for the writer. Starting
            // it earlier would let ordinary contention exhaust a short budget and
            // report contended upkeep as a backend failure.
            long deadline = System.nanoTime() + budget.timeLimit().toNanos();
            // Opened only after the writer is held: opening first would keep an
            // idle write connection for the whole wait, and discard it entirely
            // when the wait ends in a stuck-operation failure.
            try (Connection connection = SqliteArchiveSql.open(config, false)) {
            executeBounded(connection, deadline, "PRAGMA wal_checkpoint(PASSIVE)");
            executeBounded(connection, deadline, "PRAGMA optimize");
            long databaseBytes = Files.size(config.databasePath());
            long freePages = SqliteArchiveSql.scalarLong(connection, "PRAGMA freelist_count");
            if (freePages > 0 && databaseBytes > 0) {
                if (budget.maxBytesToRewrite() < databaseBytes) {
                    deferMaintenance("rewrite budget");
                } else {
                    exclusiveReaders = readers.tryAcquire(config.maxReaders());
                    if (exclusiveReaders) {
                        executeBounded(connection, deadline, "VACUUM");
                        lastMaintenanceDeferral.set(null);
                    } else {
                        deferMaintenance("active reader snapshot");
                    }
                }
            } else {
                lastMaintenanceDeferral.set(null);
            }
            health.set(ArchiveHealth.healthy());
            }
        } catch (ArchiveStuckOperationException e) {
            deferMaintenance("writer wait");
            throw e;
        } catch (Exception e) {
            recordFailure("maintenance", e);
            markDegraded("SQLite maintenance failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite maintenance failed", e);
        } finally {
            if (exclusiveReaders) readers.release(config.maxReaders());
            releaseWriter(writerTicket);
        }
    }

    @Override
    public ArchiveHealth health() { return health.get(); }

    public SqliteStorageStats storageStats() {
        requireOpen();
        long readerTicket = acquireReader("storage-stats");
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
            releaseReaderPermit(readerTicket);
        }
    }

    public void verifyIntegrity(Duration timeout) {
        requireOpen();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("integrity timeout must be positive");
        }
        long readerTicket = acquireReader("verify-integrity");
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
            releaseReaderPermit(readerTicket);
        }
    }

    public Path backup(Path target) {
        requireOpen();
        Path normalized = java.util.Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (normalized.equals(config.databasePath())) throw new IllegalArgumentException("backup target equals database");
        long writerTicket = -1;
        try {
            writerTicket = acquireWriter("backup");
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
            recordFailure("backup", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite online backup failed", e);
        } finally {
            releaseWriter(writerTicket);
        }
    }

    /** Releases exactly the supplied ticket. An unheld ticket is a no-op. */
    void releaseWriter(long ticket) {
        if (ticket >= 0) writerGate.release(ticket);
        finishCloseIfIdle();
    }

    void releaseReader(long readerTicket) {
        releaseReaderPermit(readerTicket);
    }

    private void mutateCommittedJobs(ArchiveDatasetId dataset, ArchiveRange range, boolean wholeJobsOnly) {
        requireOpen();
        if (dataset.sourceKind() != range.sourceKind()) throw new IllegalArgumentException("dataset/range source mismatch");
        long writerTicket = -1;
        try (Connection connection = SqliteArchiveSql.open(config, false)) {
            writerTicket = acquireWriter((wholeJobsOnly ? "retention " : "invalidate ") + dataset.name());
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
            recordFailure((wholeJobsOnly ? "retention " : "invalidate ") + dataset.name(), e);
            markDegraded("SQLite invalidation failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("SQLite invalidation failed", e);
        } finally {
            releaseWriter(writerTicket);
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
            // Identity conflict: retrying the same job can never succeed.
            throw new ArchiveFatalException("job ID conflicts with different committed metadata: " + job.jobId());
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

    /**
     * Waits for the single writer using the configured warn/stuck policy and
     * returns the caller's own ticket. The ticket is deliberately not stored on
     * the backend: a shared field cannot express ownership, so a caller whose
     * acquisition fails could release the active writer's permit.
     */
    private long acquireWriter(String operation) {
        long ticket = writerGate.acquire(operation);
        if (closed.get()) {
            releaseWriter(ticket);
            throw new IllegalStateException("SQLite backend is closed");
        }
        return ticket;
    }

    /** Internal worker read: ordinary contention waits under the warn/stuck policy. */
    private long acquireReader(String operation) {
        return acquireReader(operation, config.waitPolicy());
    }

    private long acquireReader(String operation, ArchiveWaitPolicy policy) {
        long ticket = readerGate.acquire(operation, policy);
        if (closed.get()) {
            releaseReaderPermit(ticket);
            throw new IllegalStateException("SQLite backend is closed");
        }
        return ticket;
    }

    private static void logWait(String gate, String operation, Duration waited, String holderDetail) {
        LOG.log(System.Logger.Level.WARNING,
                "Archive still waiting for {0} after {1}s while running {2}; {3}",
                gate, waited.toSeconds(), operation, holderDetail);
    }

    private void recordFailure(String operation, Throwable failure) {
        if (closed.get()) return;
        lastFailure.set(new ArchiveResourceDiagnostics.FailureEvent(operation,
                failure.getMessage() == null ? failure.toString() : failure.getMessage(), Instant.now()));
    }

    /** Records why bounded upkeep did not run, so repeated deferral is visible. */
    private void deferMaintenance(String reason) {
        lastMaintenanceDeferral.set(reason);
        LOG.log(System.Logger.Level.DEBUG, "SQLite archive maintenance deferred: {0}", reason);
    }

    @Override
    public ArchiveResourceDiagnostics resourceDiagnostics() {
        Optional<ArchiveResourceDiagnostics.WaitEvent> writerWarning = writerGate.lastWaitWarning();
        Optional<ArchiveResourceDiagnostics.WaitEvent> readerWarning = readerGate.lastWaitWarning();
        Optional<ArchiveResourceDiagnostics.WaitEvent> latest;
        if (writerWarning.isEmpty()) latest = readerWarning;
        else if (readerWarning.isEmpty()) latest = writerWarning;
        else latest = writerWarning.orElseThrow().at().isAfter(readerWarning.orElseThrow().at())
                    ? writerWarning : readerWarning;
        return new ArchiveResourceDiagnostics(List.of(writerGate.usage(), readerGate.usage()),
                latest, Optional.ofNullable(lastFailure.get()),
                Optional.ofNullable(lastMaintenanceDeferral.get()));
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
        // CLOSED is terminal. A mutation queued behind the writer and rejected by
        // shutdown is an expected outcome, not a health regression.
        if (closed.get()) return;
        health.set(new ArchiveHealth(ArchiveHealth.Status.DEGRADED,
                detail + ": " + error.getMessage(), Instant.now()));
    }

    private void markUnhealthy(String detail, Throwable error) {
        if (closed.get()) return;
        health.set(new ArchiveHealth(ArchiveHealth.Status.UNHEALTHY,
                detail + ": " + error.getMessage(), Instant.now()));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        health.set(new ArchiveHealth(ArchiveHealth.Status.CLOSED, "", Instant.now()));
        finishCloseIfIdle();
    }

    private void releaseReaderPermit(long readerTicket) {
        readerGate.release(readerTicket);
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
