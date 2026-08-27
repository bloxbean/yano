package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveBatchCapacityException;
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
import com.bloxbean.cardano.yano.archive.api.ArchiveResourceDiagnostics;
import com.bloxbean.cardano.yano.archive.api.ArchiveResourceGate;
import com.bloxbean.cardano.yano.archive.api.ArchiveStuckOperationException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;

/** DuckLake/SQLite-catalog backend. All historical row data is DuckLake-managed Parquet. */
public final class DuckLakeHistoryArchiveBackend implements ArchiveBackend {
    private static final System.Logger LOG =
            System.getLogger(DuckLakeHistoryArchiveBackend.class.getName());
    private static final ArchiveCapabilities CAPABILITIES = new ArchiveCapabilities(
            true, false, true, true, true);
    private static final List<String> CONTROL_TABLES = List.of(
            "archive_commit_counts",
            "archive_commits",
            "archive_coverage",
            "archive_invalidations",
            "archive_identity",
            "archive_schema");

    private final ArchiveIdentity identity;
    private final DuckLakeArchiveConfig config;
    private final DuckDbManager manager;
    private final boolean ownsManager;
    private final ArchiveDirectoryLock directoryLock;
    // A session may be closed by a different executor than the one that opened it.
    // A fair semaphore preserves single-writer FIFO semantics without thread
    // ownership; the gate adds only wait diagnostics and the warn/stuck split.
    private final ArchiveResourceGate writer;
    private final AtomicReference<ArchiveResourceDiagnostics.FailureEvent> lastFailure =
            new AtomicReference<>();
    private final AtomicReference<String> lastMaintenanceDeferral = new AtomicReference<>();
    // Stamps are not thread-owned, so a request/session may be closed by a
    // different executor while backup and shutdown still wait for quiescence.
    private final StampedLock sessionGate = new StampedLock();
    private final Map<Long, LongAdder> activeSnapshots = new ConcurrentHashMap<>();
    private final AtomicReference<ArchiveHealth> health = new AtomicReference<>(ArchiveHealth.healthy());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    // Guarded by the single-writer semaphore. A round-robin cursor prevents a
    // busy table from starving the rest of the catalog during bounded upkeep.
    private int nextCompactionTable;
    private DuckLakeTransactionLocator transactionLocator;
    private final ArchiveRepositorySet repositories = new JdbcArchiveRepositorySet(
            session -> {
                if (!(session instanceof DuckLakeReadSession duckLake)) {
                    throw new IllegalArgumentException("read session does not belong to DuckLake backend");
                }
                return duckLake.connection();
            }, "history_lake.", "history_lake.archive_coverage",
            // ADR-039: when the replay worker wrote no coverage, the projection's receipt log is
            // the equivalent proof of what is durably present.
            "history_lake." + DuckLakeProjectionSchema.RECEIPTS_TABLE);

    public DuckLakeHistoryArchiveBackend(ArchiveIdentity identity, DuckLakeArchiveConfig config,
                                         DuckDbManager manager) {
        this(identity, config, manager, false);
    }

    public static DuckLakeHistoryArchiveBackend open(ArchiveIdentity identity,
                                                      DuckLakeArchiveConfig config,
                                                      DuckDbManagerConfig managerConfig,
                                                      PackagedDuckDbExtensionLoader extensions) {
        return open(identity, config, managerConfig, extensions, config.waitPolicy());
    }

    public static DuckLakeHistoryArchiveBackend open(ArchiveIdentity identity,
                                                      DuckLakeArchiveConfig config,
                                                      DuckDbManagerConfig managerConfig,
                                                      PackagedDuckDbExtensionLoader extensions,
                                                      ArchiveWaitPolicy waitPolicy) {
        return new DuckLakeHistoryArchiveBackend(identity, config,
                new DuckDbManager(managerConfig, extensions, waitPolicy), true, waitPolicy, false);
    }

    /** Open the read facade for a projection archive without creating unselected epoch tables. */
    public static DuckLakeHistoryArchiveBackend openProjectionRead(ArchiveIdentity identity,
            DuckLakeArchiveConfig config, DuckDbManagerConfig managerConfig,
            PackagedDuckDbExtensionLoader extensions, ArchiveWaitPolicy waitPolicy) {
        return new DuckLakeHistoryArchiveBackend(identity, config,
                new DuckDbManager(managerConfig, extensions, waitPolicy), true, waitPolicy, true);
    }

    private DuckLakeHistoryArchiveBackend(ArchiveIdentity identity, DuckLakeArchiveConfig config,
                                          DuckDbManager manager, boolean ownsManager) {
        this(identity, config, manager, ownsManager, config.waitPolicy());
    }

    private DuckLakeHistoryArchiveBackend(ArchiveIdentity identity, DuckLakeArchiveConfig config,
                                          DuckDbManager manager, boolean ownsManager,
                                          ArchiveWaitPolicy waitPolicy) {
        this(identity, config, manager, ownsManager, waitPolicy, false);
    }

    private DuckLakeHistoryArchiveBackend(ArchiveIdentity identity, DuckLakeArchiveConfig config,
                                          DuckDbManager manager, boolean ownsManager,
                                          ArchiveWaitPolicy waitPolicy, boolean projectionRead) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        if (!identity.engine().equals("ducklake")) {
            throw new IllegalArgumentException("DuckLake backend requires engine=ducklake");
        }
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.manager = java.util.Objects.requireNonNull(manager, "manager");
        this.ownsManager = ownsManager;
        this.writer = new ArchiveResourceGate("ducklake-writer", 1,
                java.util.Objects.requireNonNull(waitPolicy, "waitPolicy"),
                DuckLakeHistoryArchiveBackend::logWait);
        try {
            Files.createDirectories(config.dataPath());
        } catch (Exception | Error e) {
            if (ownsManager) manager.close();
            throw new ArchiveStoreException("cannot create DuckLake data directory", e);
        }
        this.directoryLock = new ArchiveDirectoryLock(config.catalogPath());
        try (DuckDbLease lease = manager.acquire(DuckDbWorkload.BULK_CATCH_UP, config.acquireTimeout())) {
            DuckLakeSql.attach(lease.connection(), config, null, false);
            try {
                if (projectionRead) {
                    new DuckLakeInitializer(config).initializeProjection(lease.connection(), identity);
                } else {
                    new DuckLakeInitializer(config).initialize(lease.connection(), identity);
                }
                transactionLocator = new DuckLakeTransactionLocator(config.catalogPath());
                transactionLocator.rebuildIfRequired(lease.connection(), DuckLakeSql.currentSnapshot(lease.connection()));
            } finally {
                DuckLakeSql.detach(lease.connection());
            }
        } catch (Exception e) {
            directoryLock.close();
            if (ownsManager) manager.close();
            markUnhealthy("DuckLake initialization failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("DuckLake initialization failed", e);
        }
    }

    @Override
    public ArchiveIdentity identity() {
        return identity;
    }

    @Override
    public ArchiveCapabilities capabilities() {
        return CAPABILITIES;
    }

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
        String operation = "begin " + job.dataset().name();
        // Resource order invariant: DuckDB capacity, then the archive writer.
        // Taking the writer first would let a capacity wait occupy the single
        // writer permit and stall every other projection behind it.
        DuckDbLease lease = null;
        long ticket = -1;
        boolean leaseClosed = false;
        try {
            // Inside the guarded scope so a stuck capacity or writer wait is
            // recorded like any other mutation failure. Order is unchanged:
            // DuckDB capacity, then the archive writer, then the transaction.
            lease = acquireCapacity(operation);
            ticket = acquireWriter(operation);
            DuckLakeSql.attach(lease.connection(), config, null, false);
            Optional<ArchiveReceipt> replay = findReceipt(lease.connection(), job.jobId());
            if (replay.isPresent()) {
                verifyReplayMetadata(job, replay.orElseThrow());
                DuckLakeSql.detach(lease.connection());
                lease.close();
                leaseClosed = true;
                return new DuckLakeWriteSession(this, job, null, replay.orElseThrow(), ticket);
            }
            rejectCoverageOverlap(lease.connection(), job);
            return new DuckLakeWriteSession(this, job, lease, null, ticket);
        } catch (Exception e) {
            // Detach and close the lease before releasing the writer: handing the
            // permit over while this connection still has the catalog attached
            // would let the next mutation run DDL against it.
            if (lease != null && !leaseClosed) {
                try { DuckLakeSql.detach(lease.connection()); } catch (SQLException | RuntimeException ignored) { }
                lease.close();
            }
            releaseWriter(ticket);
            recordFailure(operation, e);
            markDegraded("DuckLake begin failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("failed to begin DuckLake job", e);
        }
    }

    /**
     * Acquires bounded DuckDB capacity before the writer. A caller that cannot
     * get capacity never occupies the writer semaphore while it waits.
     */
    private DuckDbLease acquireCapacity(String operation) {
        try {
            return manager.acquire(DuckDbWorkload.BULK_CATCH_UP, config.waitPolicy(), operation);
        } catch (SQLException e) {
            throw new ArchiveStoreException("failed to acquire DuckDB capacity for " + operation, e);
        }
    }

    @Override
    public Optional<ArchiveReceipt> findReceipt(UUID jobId) {
        requireOpen();
        try (CurrentRead read = currentRead()) {
            return findReceipt(read.connection(), jobId);
        } catch (SQLException e) {
            markDegraded("DuckLake receipt read failed", e);
            throw new ArchiveStoreException("failed to read DuckLake receipt", e);
        }
    }

    @Override
    public ArchiveCoverage coverage(ArchiveDatasetId dataset) {
        requireOpen();
        try (CurrentRead read = currentRead()) {
            return coverage(read.connection(), DuckLakeSql.currentSnapshot(read.connection()), dataset);
        } catch (SQLException e) {
            markDegraded("DuckLake coverage read failed", e);
            throw new ArchiveStoreException("failed to read DuckLake coverage", e);
        }
    }

    @Override
    public ArchiveCoverage coverage(ArchiveReadSession session, ArchiveDatasetId dataset) {
        requireOpen();
        if (!(session instanceof DuckLakeReadSession read)) {
            throw new IllegalArgumentException("read session does not belong to DuckLake backend");
        }
        try {
            return coverage(read.connection(), read.generation(), dataset);
        } catch (SQLException e) {
            markDegraded("DuckLake pinned coverage read failed", e);
            throw new ArchiveStoreException("failed to read pinned DuckLake coverage", e);
        }
    }

    @Override
    public Optional<ArchiveCommitBoundary> latestBlockBoundary(
            ArchiveReadSession session, ArchiveDatasetId dataset,
            BlockRange range, OptionalLong atOrBeforeSlot) {
        requireOpen();
        if (!(session instanceof DuckLakeReadSession read)) {
            throw new IllegalArgumentException("read session does not belong to DuckLake backend");
        }
        if (dataset.sourceKind() != SourceKind.BLOCK) {
            throw new IllegalArgumentException("block boundary requires a block dataset");
        }
        String slotPredicate = atOrBeforeSlot.isPresent() ? " AND c.end_slot<=?" : "";
        try (PreparedStatement query = read.connection().prepareStatement(
                "SELECT c.projection_version, c.range_start, c.range_end, c.start_slot, c.start_hash, "
                        + "c.end_slot, c.end_hash, c.backend_generation FROM history_lake.archive_commits c "
                        + "JOIN history_lake.archive_coverage v ON v.job_id=c.job_id "
                        + "WHERE c.dataset=? AND c.source_kind='BLOCK' AND c.range_end BETWEEN ? AND ?"
                        + slotPredicate + " ORDER BY c.range_end DESC LIMIT 1")) {
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
            markDegraded("DuckLake block boundary read failed", e);
            throw new ArchiveStoreException("failed to read DuckLake block boundary", e);
        }
    }

    @Override
    public ArchiveReadSession openReadSession() {
        long gateStamp = sessionGate.readLock();
        DuckDbLease lease = null;
        try {
            requireOpen();
            // Request-facing: a query must fail fast under saturation rather than
            // wait for the stuck threshold, so the API can answer unavailable.
            lease = manager.acquire(DuckDbWorkload.STEADY, config.acquireTimeout());
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, true);
            long snapshot = DuckLakeSql.currentSnapshot(connection);
            DuckLakeSql.detach(connection);
            DuckLakeSql.attach(connection, config, snapshot, true);
            retainSnapshot(snapshot);
            return new DuckLakeReadSession(this, snapshot, lease, () -> releaseRead(gateStamp));
        } catch (Exception | Error e) {
            if (lease != null) lease.close();
            releaseRead(gateStamp);
            if (isRequestCapacityTimeout(e)) {
                // Every query permit was busy for this request's bounded wait.
                // That is contention, so the request is refused but the archive
                // itself stays healthy; degrading here would outlive the burst.
                throw new ArchiveStoreException(
                        "DuckLake read capacity is saturated; retry shortly", e);
            }
            markDegraded("DuckLake snapshot open failed", e);
            throw new ArchiveStoreException("failed to open pinned DuckLake snapshot", e);
        }
    }

    @Override
    public ArchiveRepositorySet repositories() {
        return repositories;
    }

    @Override
    public Optional<com.bloxbean.cardano.yano.archive.api.ArchiveRecord> findTransaction(
            ArchiveReadSession session, byte[] txHash) {
        if (!(session instanceof DuckLakeReadSession read)) {
            throw new IllegalArgumentException("read session does not belong to DuckLake backend");
        }
        var block = transactionLocator.block(read.connection(), read.generation(), txHash);
        var query = new com.bloxbean.cardano.yano.archive.api.ArchiveQuery(
                block.isPresent() ? new BlockRange(block.getAsLong(), block.getAsLong())
                        : new BlockRange(0, Long.MAX_VALUE), Map.of("tx_hash", txHash),
                com.bloxbean.cardano.yano.archive.api.ArchivePageCursor.Order.ASC, 1, Optional.empty());
        Optional<com.bloxbean.cardano.yano.archive.api.ArchiveRecord> located = repositories
                .records(ArchiveDatasetId.TRANSACTION).query(session, query).rows().stream().findFirst();
        if (located.isPresent() || block.isEmpty()) return located;
        // The locator is an accelerator only. A stale positive must never turn
        // into a false negative against the pinned authoritative snapshot.
        var fallback = new com.bloxbean.cardano.yano.archive.api.ArchiveQuery(
                new BlockRange(0, Long.MAX_VALUE), Map.of("tx_hash", txHash),
                com.bloxbean.cardano.yano.archive.api.ArchivePageCursor.Order.ASC, 1, Optional.empty());
        return repositories.records(ArchiveDatasetId.TRANSACTION).query(session, fallback)
                .rows().stream().findFirst();
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
        String operation = "invalidate-epoch-jobs " + dataset.name();
        long ticket = -1;
        try (DuckDbLease lease = acquireCapacity(operation)) {
            ticket = acquireWriter(operation);
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, false);
            try {
                try (Statement sql = connection.createStatement()) { sql.execute("BEGIN TRANSACTION"); }
                List<UUID> jobs = new ArrayList<>();
                long firstEpoch = Long.MAX_VALUE;
                long lastEpoch = -1;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT job_id, range_start, range_end FROM history_lake.archive_commits "
                                + "WHERE dataset=? AND source_kind='EPOCH' AND end_slot>?")) {
                    query.setString(1, dataset.name());
                    query.setLong(2, rollbackSlot);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            jobs.add(UUID.fromString(rows.getString(1)));
                            firstEpoch = Math.min(firstEpoch, rows.getLong(2));
                            lastEpoch = Math.max(lastEpoch, rows.getLong(3));
                        }
                    }
                }
                if (jobs.isEmpty()) {
                    try (Statement sql = connection.createStatement()) { sql.execute("ROLLBACK"); }
                    return 0;
                }
                for (UUID job : jobs) deleteJobRows(connection, dataset, job);
                insertInvalidation(connection, dataset, new EpochRange(firstEpoch, lastEpoch));
                try (Statement sql = connection.createStatement()) { sql.execute("COMMIT"); }
                return jobs.size();
            } catch (Exception e) {
                try (Statement sql = connection.createStatement()) { sql.execute("ROLLBACK"); }
                catch (SQLException ignored) { }
                throw e;
            } finally {
                DuckLakeSql.detach(connection);
            }
        } catch (Exception e) {
            recordFailure(operation, e);
            markDegraded("DuckLake epoch rollback invalidation failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("DuckLake epoch rollback invalidation failed", e);
        } finally {
            releaseWriter(ticket);
        }
    }

    @Override
    public void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff) {
        if (dataset.sourceKind() != cutoff.sourceKind()) {
            throw new IllegalArgumentException("retention cutoff source does not match dataset");
        }
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
        // Check for pinned readers before taking any resource. Maintenance that
        // will immediately defer must not burn the scarce bulk permit or the
        // writer to discover that.
        if (!activeSnapshots.isEmpty()) {
            deferMaintenance("active reader snapshot");
            return;
        }
        String operation = "maintenance";
        long ticket = -1;
        try (DuckDbLease lease = acquireCapacity(operation)) {
            ticket = acquireWriter(operation);
            // A reader may have pinned a snapshot while capacity was awaited.
            if (!activeSnapshots.isEmpty()) {
                deferMaintenance("active reader snapshot");
                return;
            }
            DuckLakeSql.attach(lease.connection(), config, null, false);
            ArchiveBatchCapacityException compactionDeferred = null;
            try {
                long deadline = System.nanoTime() + budget.timeLimit().toNanos();
                if (budget.maxBytesToRewrite() > 0) {
                    List<String> tables = maintenanceTables();
                    int maxFiles = compactionOutputsPerTable(budget.maxBytesToRewrite(),
                            config.targetFileSizeBytes(), tables.size());
                    int completedTables = 0;
                    while (completedTables < tables.size() && hasMaintenanceTime(deadline)) {
                        int index = Math.floorMod(nextCompactionTable, tables.size());
                        String table = tables.get(index);
                        try {
                            // A catalog-wide call applies max_compacted_files once per table and
                            // rolls the whole call back on timeout. Independent table calls retain
                            // progress made before the bounded maintenance window expires.
                            executeMaintenance(lease, deadline, compactionCommand(table,
                                    compactionOutputsForTable(table, maxFiles)));
                            nextCompactionTable = (index + 1) % tables.size();
                            completedTables++;
                        } catch (SQLException e) {
                            if (!DuckLakeWriteSession.isCapacityFailure(e)) throw e;
                            // Do not pin every future maintenance cycle to one table whose
                            // current payload cannot be compacted within the reserved memory.
                            // The next full round retries it after all other tables have had a
                            // bounded opportunity to compact.
                            nextCompactionTable = (index + 1) % tables.size();
                            // Earlier table calls are separate committed snapshots. Reaching the
                            // time/memory edge after useful progress is normal bounded upkeep; the
                            // cursor retries this table with a fresh window next cycle. A table
                            // that cannot make any progress with the full window remains visible.
                            if (completedTables == 0) {
                                compactionDeferred = new ArchiveBatchCapacityException(
                                        "DuckLake compaction exceeded its configured budget at table " + table, e);
                            }
                            break;
                        }
                    }
                }
                if (compactionDeferred == null && hasMaintenanceTime(deadline)) {
                    executeMaintenance(lease, deadline,
                            "CALL ducklake_expire_snapshots('history_lake', older_than => now() - INTERVAL '"
                                    + config.snapshotRetention().toSeconds() + " seconds')");
                }
                if (compactionDeferred == null && hasMaintenanceTime(deadline)) {
                    executeMaintenance(lease, deadline,
                            "CALL ducklake_cleanup_old_files('history_lake', older_than => now() - INTERVAL '"
                                    + config.cleanupGrace().toSeconds() + " seconds')");
                }
                if (compactionDeferred == null && hasMaintenanceTime(deadline)) {
                    executeMaintenance(lease, deadline,
                            "CALL ducklake_delete_orphaned_files('history_lake', older_than => now() - INTERVAL '"
                                    + config.cleanupGrace().toSeconds() + " seconds')");
                }
            } finally {
                DuckLakeSql.detach(lease.connection());
            }
            health.set(ArchiveHealth.healthy());
            if (compactionDeferred != null) {
                deferMaintenance("rewrite budget");
                throw compactionDeferred;
            }
            lastMaintenanceDeferral.set(null);
        } catch (ArchiveBatchCapacityException e) {
            // Compaction/cleanup is optional and its bounded resource pressure
            // does not make committed archive data unhealthy. Record the reason
            // here too, or a capacity deferral raised outside the tracked
            // compaction variable leaves a stale reason from an earlier run.
            health.set(ArchiveHealth.healthy());
            deferMaintenance("rewrite budget");
            throw e;
        } catch (ArchiveStuckOperationException e) {
            // With capacity acquired before the writer, a stuck wait can come from
            // either gate; naming the wrong one defeats the diagnostic.
            deferMaintenance(e.gate().contains("writer") ? "writer wait" : "capacity wait");
            throw e;
        } catch (Exception e) {
            if (!degradesArchiveHealth(e)) {
                health.set(ArchiveHealth.healthy());
                deferMaintenance(isMaintenanceTimeout(e) ? "time budget" : "rewrite budget");
                throw new ArchiveBatchCapacityException(
                        "DuckLake maintenance exceeded its configured budget", e);
            }
            recordFailure("maintenance", e);
            markDegraded("DuckLake maintenance failed", e);
            throw new ArchiveStoreException("DuckLake maintenance failed", e);
        } finally {
            releaseWriter(ticket);
        }
    }

    /**
     * Records why bounded upkeep did not run. Repeated deferral is an
     * operational health signal, not a silent no-op.
     */
    private void deferMaintenance(String reason) {
        lastMaintenanceDeferral.set(reason);
        LOG.log(System.Logger.Level.DEBUG, "DuckLake maintenance deferred: {0}", reason);
    }

    /**
     * DuckLake applies {@code max_compacted_files} independently to every table
     * when no table name is supplied. Divide the aggregate rewrite allowance by
     * the number of archive tables so the configured budget remains aggregate
     * rather than being multiplied once per table.
     */
    static int compactionOutputsPerTable(long maxBytesToRewrite, long targetFileSizeBytes, int tableCount) {
        if (maxBytesToRewrite < 1 || targetFileSizeBytes < 1 || tableCount < 1) {
            throw new IllegalArgumentException("invalid DuckLake compaction sizing inputs");
        }
        long aggregateOutputs = Math.max(1, maxBytesToRewrite / targetFileSizeBytes);
        return (int) Math.max(1, Math.min(100, aggregateOutputs / tableCount));
    }

    static List<String> maintenanceTables() {
        List<String> tables = new ArrayList<>(CONTROL_TABLES);
        DuckLakeSql.tables().keySet().stream().sorted().forEach(tables::add);
        return List.copyOf(tables);
    }

    static String compactionCommand(String table, int maxFiles) {
        if (maxFiles < 1) throw new IllegalArgumentException("maxFiles must be positive");
        return "CALL ducklake_merge_adjacent_files('history_lake', '"
                + DuckLakeSql.literal(DuckLakeSql.name(table))
                + "', max_compacted_files => " + maxFiles + ")";
    }

    static int compactionOutputsForTable(String table, int sharedLimit) {
        if (sharedLimit < 1) throw new IllegalArgumentException("sharedLimit must be positive");
        return switch (DuckLakeSql.name(table)) {
            // These append-only control rows are tiny but are written for every archive job.
            // More outputs here stay well below the byte budget and prevent metadata-file
            // growth from dominating all data-table maintenance.
            case "archive_commit_counts", "archive_commits", "archive_coverage" ->
                    Math.min(100, Math.multiplyExact(sharedLimit, 100));
            default -> sharedLimit;
        };
    }

    private static boolean hasMaintenanceTime(long deadline) {
        return deadline - System.nanoTime() >= TimeUnit.SECONDS.toNanos(1);
    }

    static boolean degradesArchiveHealth(Throwable maintenanceFailure) {
        return !(maintenanceFailure instanceof ArchiveBatchCapacityException)
                && !DuckLakeWriteSession.isCapacityFailure(maintenanceFailure)
                && !isMaintenanceTimeout(maintenanceFailure);
    }

    /**
     * DuckDB reports a JDBC statement query timeout as an interrupt error. In
     * this backend statements are interrupted only inside the explicitly
     * bounded maintenance path, so the outcome means "retry next cycle", not
     * that committed archive data is unhealthy.
     */
    static boolean isMaintenanceTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("INTERRUPT Error: Interrupted")
                    || message.contains("Query interrupted"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ArchiveHealth health() {
        return health.get();
    }

    /** Bounded explicit metadata/catalog integrity check; never runs on core threads. */
    public void verifyIntegrity(Duration timeout) {
        requireOpen();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("integrity timeout must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try (CurrentRead read = currentRead(); Statement sql = boundedStatement(read.connection(), deadline)) {
            try (ResultSet result = sql.executeQuery("SELECT "
                    + "(SELECT count(*) FROM history_lake.archive_identity), "
                    + "(SELECT count(*) FROM (SELECT job_id FROM history_lake.archive_commits GROUP BY job_id HAVING count(*) > 1)), "
                    + "(SELECT count(*) FROM history_lake.archive_coverage c LEFT JOIN history_lake.archive_commits j "
                    + "ON c.job_id=j.job_id WHERE j.job_id IS NULL)")) {
                result.next();
                if (result.getLong(1) != 1 || result.getLong(2) != 0 || result.getLong(3) != 0) {
                    throw new ArchiveStoreException("DuckLake archive metadata integrity check failed");
                }
            }
            // Scan every stable table so missing/corrupt committed Parquet is detected.
            for (String table : DuckLakeSql.tables().keySet()) {
                try (Statement tableScan = boundedStatement(read.connection(), deadline)) {
                    tableScan.executeQuery("SELECT count(*) FROM history_lake."
                            + DuckLakeSql.name(table)).close();
                }
            }
            health.set(ArchiveHealth.healthy());
        } catch (Exception e) {
            markUnhealthy("DuckLake integrity check failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("DuckLake integrity check failed", e);
        }
    }

    /**
     * Copies a quiescent SQLite catalog to an atomically published backup file.
     * DuckLake Parquet data must be backed up with the same generation.
     */
    public Path backupCatalog(Path target) {
        requireOpen();
        Path normalized = java.util.Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (normalized.equals(config.catalogPath())) throw new IllegalArgumentException("backup target equals catalog");
        // Backup is the one path that holds the reader gate and the writer but
        // never a DuckDB lease, so it cannot participate in the lease->writer
        // inversion. It drains readers first and then takes the writer with a
        // short bounded wait: holding the reader gate for the full stuck
        // threshold would block new queries for minutes.
        long gateStamp = 0;
        long ticket = -1;
        try {
            gateStamp = sessionGate.tryWriteLock(config.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (gateStamp == 0) {
                throw new ArchiveStoreException("timed out waiting for DuckLake readers before catalog backup");
            }
            ticket = acquireWriter("catalog-backup", config.waitPolicy().boundedTo(config.acquireTimeout()));
            Path parent = normalized.getParent();
            if (parent == null) throw new ArchiveStoreException("catalog backup target has no parent");
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, normalized.getFileName().toString(), ".tmp");
            try {
                Files.copy(config.catalogPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArchiveStoreException("interrupted waiting for DuckLake readers before catalog backup", e);
        } catch (Exception e) {
            recordFailure("catalog-backup", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("DuckLake catalog backup failed", e);
        } finally {
            releaseWriter(ticket);
            if (gateStamp != 0) sessionGate.unlockWrite(gateStamp);
        }
    }

    /** Releases exactly the supplied ticket. An unheld ticket is a no-op. */
    void releaseWriter(long ticket) {
        if (ticket >= 0) writer.release(ticket);
        finishCloseIfIdle();
    }

    void updateTransactionLocator(Connection connection, long generation,
                                  Collection<DuckLakeTransactionLocator.Entry> entries) {
        transactionLocator.advance(connection, generation, entries);
    }

    void releaseSnapshot(long generation) {
        activeSnapshots.computeIfPresent(generation, (ignored, count) -> {
            count.decrement();
            return count.sum() <= 0 ? null : count;
        });
    }

    private void retainSnapshot(long generation) {
        activeSnapshots.computeIfAbsent(generation, ignored -> new LongAdder()).increment();
    }

    private void mutateCommittedJobs(ArchiveDatasetId dataset, ArchiveRange range, boolean wholeJobsOnly) {
        requireOpen();
        if (dataset.sourceKind() != range.sourceKind()) throw new IllegalArgumentException("dataset/range source mismatch");
        String operation = (wholeJobsOnly ? "retention " : "invalidate ") + dataset.name();
        long ticket = -1;
        try (DuckDbLease lease = acquireCapacity(operation)) {
            ticket = acquireWriter(operation);
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, false);
            try {
                try (Statement sql = connection.createStatement()) { sql.execute("BEGIN TRANSACTION"); }
                List<UUID> jobs = findAffectedJobs(connection, dataset, range, wholeJobsOnly);
                if (wholeJobsOnly && jobs.isEmpty()) {
                    try (Statement sql = connection.createStatement()) { sql.execute("ROLLBACK"); }
                    return;
                }
                for (UUID jobId : jobs) deleteJobRows(connection, dataset, jobId);
                insertInvalidation(connection, dataset, range);
                try (Statement sql = connection.createStatement()) { sql.execute("COMMIT"); }
                if (dataset == ArchiveDatasetId.TRANSACTION) {
                    transactionLocator.rebuild(connection, DuckLakeSql.currentSnapshot(connection));
                }
            } catch (Exception e) {
                try (Statement sql = connection.createStatement()) { sql.execute("ROLLBACK"); }
                catch (SQLException ignored) { }
                throw e;
            } finally {
                DuckLakeSql.detach(connection);
            }
        } catch (Exception e) {
            recordFailure(operation, e);
            markDegraded("DuckLake invalidation failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("DuckLake invalidation failed", e);
        } finally {
            releaseWriter(ticket);
        }
    }

    private List<UUID> findAffectedJobs(Connection connection, ArchiveDatasetId dataset,
                                        ArchiveRange range, boolean wholeJobsOnly) throws SQLException {
        String predicate = wholeJobsOnly ? "range_end <= ?" : "NOT (range_end < ? OR range_start > ?)";
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT job_id FROM history_lake.archive_coverage WHERE dataset=? AND " + predicate)) {
            query.setString(1, dataset.name());
            if (wholeJobsOnly) query.setLong(2, range.endInclusive());
            else {
                query.setLong(2, range.startInclusive());
                query.setLong(3, range.endInclusive());
            }
            List<UUID> jobs = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) jobs.add(UUID.fromString(rows.getString(1)));
            }
            return jobs;
        }
    }

    private void deleteJobRows(Connection connection, ArchiveDatasetId dataset, UUID jobId) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            for (var table : ArchiveSchemas.schema(dataset).tables()) {
                if (table.columns().stream().anyMatch(column -> column.name().equals("archive_job_id"))) {
                    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM history_lake."
                            + DuckLakeSql.name(table.physicalName()) + " WHERE archive_job_id=?")) {
                        delete.setObject(1, jobId);
                        delete.executeUpdate();
                    }
                }
            }
        }
        for (String table : List.of("archive_commit_counts", "archive_coverage", "archive_commits")) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM history_lake." + table + " WHERE job_id=?")) {
                delete.setObject(1, jobId);
                delete.executeUpdate();
            }
        }
    }

    private void insertInvalidation(Connection connection, ArchiveDatasetId dataset, ArchiveRange range)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history_lake.archive_invalidations VALUES (?, ?, ?, ?, ?, current_timestamp)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, dataset.name());
            insert.setString(3, range.sourceKind().name());
            insert.setLong(4, range.startInclusive());
            insert.setLong(5, range.endInclusive());
            insert.executeUpdate();
        }
    }

    private void rejectCoverageOverlap(Connection connection, ArchiveJob job) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM history_lake.archive_coverage WHERE dataset=? "
                        + "AND NOT (range_end < ? OR range_start > ?)")) {
            query.setString(1, job.dataset().name());
            query.setLong(2, job.range().startInclusive());
            query.setLong(3, job.range().endInclusive());
            try (ResultSet row = query.executeQuery()) {
                row.next();
                if (row.getLong(1) != 0) {
                    throw new ArchiveStoreException("archive job overlaps committed coverage for " + job.dataset());
                }
            }
        }
    }

    private Optional<ArchiveReceipt> findReceipt(Connection connection, UUID jobId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT dataset, projection_version, source_kind, range_start, range_end, start_slot, start_hash, "
                        + "end_slot, end_hash, backend_generation, ordered_digest, committed_at "
                        + "FROM history_lake.archive_commits WHERE job_id=?")) {
            query.setObject(1, jobId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                ArchiveDatasetId dataset = ArchiveDatasetId.valueOf(row.getString(1));
                SourceKind kind = SourceKind.valueOf(row.getString(3));
                ArchiveRange range = kind == SourceKind.BLOCK
                        ? new BlockRange(row.getLong(4), row.getLong(5))
                        : new EpochRange(row.getLong(4), row.getLong(5));
                ArchiveRangeAnchor anchors = new ArchiveRangeAnchor(row.getLong(6), row.getBytes(7),
                        row.getLong(8), row.getBytes(9));
                ArchiveReceipt receipt = new ArchiveReceipt(jobId, identity.networkIdentity(), dataset,
                        row.getInt(2), range, anchors, row.getLong(10), readCounts(connection, jobId),
                        row.getString(11), row.getTimestamp(12).toInstant());
                if (row.next()) throw new ArchiveStoreException("duplicate archive receipt for " + jobId);
                return Optional.of(receipt);
            }
        }
    }

    private Map<String, Long> readCounts(Connection connection, UUID jobId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT table_name, row_count FROM history_lake.archive_commit_counts WHERE job_id=?")) {
            query.setObject(1, jobId);
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

    /**
     * Internal archive read (receipts, coverage, integrity). This is worker-side
     * work, so capacity contention follows the warn/stuck policy rather than
     * failing at a short timeout. Request-facing {@link #openReadSession()} keeps
     * a bounded wait so an API call cannot hang for the stuck threshold.
     */
    private CurrentRead currentRead() throws SQLException {
        long gateStamp = sessionGate.readLock();
        DuckDbLease lease = null;
        try {
            requireOpen();
            lease = manager.acquire(DuckDbWorkload.STEADY, config.waitPolicy(), "archive-read");
            DuckLakeSql.attach(lease.connection(), config, null, true);
            return new CurrentRead(lease, () -> releaseRead(gateStamp));
        } catch (SQLException | RuntimeException | Error e) {
            if (lease != null) lease.close();
            releaseRead(gateStamp);
            throw e;
        }
    }

    private ArchiveCoverage coverage(Connection connection, long revision, ArchiveDatasetId dataset)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT projection_version, source_kind, range_start, range_end "
                        + "FROM history_lake.archive_coverage WHERE dataset=? ORDER BY range_start")) {
            query.setString(1, dataset.name());
            List<ArchiveRange> ranges = new ArrayList<>();
            int projectionVersion = ArchiveSchemas.schema(dataset).projectionVersion();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    projectionVersion = rows.getInt(1);
                    SourceKind kind = SourceKind.valueOf(rows.getString(2));
                    long start = rows.getLong(3);
                    long end = rows.getLong(4);
                    ranges.add(kind == SourceKind.BLOCK ? new BlockRange(start, end) : new EpochRange(start, end));
                }
            }
            // ADR-039: the projection never writes archive_coverage - that table describes
            // replay-worker jobs. Its equivalent proof is the receipt log, where every required
            // section for a block range committed in one transaction. Fall back only when the
            // worker wrote nothing, so a genuinely incomplete legacy archive still reads as
            // incomplete; that keeps the legacy answer authoritative for the Phase 7 oracle run.
            //
            // Receipts prove BLOCK ranges only. An epoch dataset is asked about in epoch terms and
            // a receipt cannot say which epochs a block range spans, so epoch coverage stays empty
            // rather than being fabricated - those reads fail closed instead.
            if (ranges.isEmpty() && dataset.sourceKind() == SourceKind.BLOCK) {
                ranges.addAll(receiptRanges(connection));
            } else if (ranges.isEmpty() && dataset.sourceKind() == SourceKind.EPOCH) {
                ranges.addAll(projectionEpochRanges(connection, dataset));
            }
            return new ArchiveCoverage(dataset, projectionVersion, revision, mergeAdjacent(ranges));
        }
    }

    /** COMPLETE outcomes written atomically with projection artifact rows. */
    private List<ArchiveRange> projectionEpochRanges(Connection connection, ArchiveDatasetId dataset)
            throws SQLException {
        List<ArchiveRange> ranges = new ArrayList<>();
        if (!tableExists(connection, DuckLakeProjectionSchema.EPOCH_COVERAGE_TABLE)) return ranges;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT DISTINCT semantic_epoch FROM history_lake."
                        + DuckLakeProjectionSchema.EPOCH_COVERAGE_TABLE
                        + " WHERE dataset=? AND outcome='COMPLETE' ORDER BY semantic_epoch")) {
            query.setString(1, dataset.name());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long epoch = rows.getLong(1);
                    ranges.add(new EpochRange(epoch, epoch));
                }
            }
        }
        return ranges;
    }

    /**
     * Contiguous block ranges the ADR-039 projection has durably committed.
     *
     * <p>Returns nothing when the receipt table is absent, which is the normal state of an
     * archive the legacy worker wrote. Checked rather than caught, and never created from here:
     * a read path must not mutate the archive's schema.
     */
    private List<ArchiveRange> receiptRanges(Connection connection) throws SQLException {
        List<ArchiveRange> ranges = new ArrayList<>();
        if (!tableExists(connection, DuckLakeProjectionSchema.RECEIPTS_TABLE)) return ranges;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT first_block, last_block FROM history_lake."
                        + DuckLakeProjectionSchema.RECEIPTS_TABLE + " ORDER BY first_block");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                ranges.add(new BlockRange(rows.getLong(1), rows.getLong(2)));
            }
        }
        return ranges;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_name = ? LIMIT 1")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private List<ArchiveRange> mergeAdjacent(List<ArchiveRange> ranges) {
        if (ranges.isEmpty()) return List.of();
        List<ArchiveRange> merged = new ArrayList<>();
        ArchiveRange current = ranges.getFirst();
        for (int i = 1; i < ranges.size(); i++) {
            ArchiveRange next = ranges.get(i);
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

    /**
     * Waits for the single writer using the configured warn/stuck policy and
     * returns the caller's own ticket.
     *
     * <p>The ticket is deliberately not stored on the backend. A shared field
     * cannot express ownership: a caller whose acquisition fails would release
     * whichever ticket happened to be there, handing the active writer's permit
     * to a third operation and breaking single-writer isolation exactly on the
     * new stuck-operation path.
     */
    private long acquireWriter(String operation) {
        return acquireWriter(operation, config.waitPolicy());
    }

    private long acquireWriter(String operation, ArchiveWaitPolicy policy) {
        long ticket = writer.acquire(operation, policy);
        if (closed.get()) {
            releaseWriter(ticket);
            throw new IllegalStateException("DuckLake backend is closed");
        }
        return ticket;
    }

    private static void logWait(String gate, String operation, Duration waited, String holderDetail) {
        LOG.log(System.Logger.Level.WARNING,
                "Archive still waiting for {0} after {1}s while running {2}; {3}",
                gate, waited.toSeconds(), operation, holderDetail);
    }

    private void recordFailure(String operation, Throwable failure) {
        // A shutdown-rejected mutation is expected, not a diagnostic worth keeping.
        if (closed.get()) return;
        lastFailure.set(new ArchiveResourceDiagnostics.FailureEvent(operation,
                failure.getMessage() == null ? failure.toString() : failure.getMessage(), Instant.now()));
    }

    @Override
    public ArchiveResourceDiagnostics resourceDiagnostics() {
        List<ArchiveResourceDiagnostics.GateUsage> gates = new ArrayList<>();
        gates.add(writer.usage());
        gates.addAll(manager.gateUsage());
        Optional<ArchiveResourceDiagnostics.WaitEvent> writerWarning = writer.lastWaitWarning();
        Optional<ArchiveResourceDiagnostics.WaitEvent> capacityWarning = manager.lastWaitWarning();
        Optional<ArchiveResourceDiagnostics.WaitEvent> latest;
        if (writerWarning.isEmpty()) latest = capacityWarning;
        else if (capacityWarning.isEmpty()) latest = writerWarning;
        else latest = writerWarning.orElseThrow().at().isAfter(capacityWarning.orElseThrow().at())
                    ? writerWarning : capacityWarning;
        return new ArchiveResourceDiagnostics(gates, latest, Optional.ofNullable(lastFailure.get()),
                Optional.ofNullable(lastMaintenanceDeferral.get()));
    }

    private void executeMaintenance(DuckDbLease lease, long deadline, String command) throws SQLException {
        try (Statement sql = boundedStatement(lease.connection(), deadline)) {
            sql.execute(command);
        }
    }

    private Statement boundedStatement(Connection connection, long deadline) throws SQLException {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) throw new ArchiveStoreException("DuckLake operation exceeded its time budget");
        Statement statement = connection.createStatement();
        long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remainingNanos));
        statement.setQueryTimeout(Math.toIntExact(Math.min(Integer.MAX_VALUE, seconds)));
        return statement;
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("DuckLake backend is closed");
    }

    /**
     * True for a bounded request-facing capacity timeout. A raw
     * {@link ArchiveStuckOperationException} from a worker path is deliberately
     * excluded: exceeding the operator's stuck threshold is a real problem.
     */
    static boolean isRequestCapacityTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof DuckDbCapacityTimeoutException) return true;
        }
        return false;
    }

    private void markDegraded(String detail, Throwable error) {
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

    private void releaseRead(long gateStamp) {
        sessionGate.unlockRead(gateStamp);
        finishCloseIfIdle();
    }

    /**
     * Close native resources only after all readers and the writer are idle.
     * This method never waits: shutdown marks the backend closed immediately,
     * and the last active session completes deferred cleanup. Forcibly closing
     * DuckDB/JNI handles underneath a stuck request would reintroduce the
     * use-after-free race this gate is intended to prevent.
     */
    private void finishCloseIfIdle() {
        if (!closed.get() || resourcesClosed.get()) return;
        Optional<Long> ticket = writer.tryAcquire("deferred-close");
        if (ticket.isEmpty()) return;
        long gateStamp = sessionGate.tryWriteLock();
        if (gateStamp == 0) {
            writer.release(ticket.orElseThrow());
            return;
        }
        try {
            if (resourcesClosed.compareAndSet(false, true)) {
                if (transactionLocator != null) transactionLocator.close();
                if (ownsManager) manager.close();
                directoryLock.close();
            }
        } finally {
            sessionGate.unlockWrite(gateStamp);
            writer.release(ticket.orElseThrow());
        }
    }

    private static final class CurrentRead implements AutoCloseable {
        private final DuckDbLease lease;
        private final Runnable releaseGate;

        private CurrentRead(DuckDbLease lease, Runnable releaseGate) {
            this.lease = lease;
            this.releaseGate = releaseGate;
        }

        Connection connection() {
            return lease.connection();
        }

        @Override
        public void close() {
            try {
                try { DuckLakeSql.detach(lease.connection()); } catch (SQLException ignored) { }
                lease.close();
            } finally {
                releaseGate.run();
            }
        }
    }
}
