package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveCapabilities;
import com.bloxbean.cardano.yano.archive.api.ArchiveCommitBoundary;
import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveHealth;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchivePageCursor;
import com.bloxbean.cardano.yano.archive.api.ArchiveQuery;
import com.bloxbean.cardano.yano.archive.api.ArchiveRange;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;
import com.bloxbean.cardano.yano.archive.api.ArchiveRecord;
import com.bloxbean.cardano.yano.archive.api.ArchiveRepositorySet;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.EpochRange;
import com.bloxbean.cardano.yano.archive.api.SourceKind;
import com.bloxbean.cardano.yano.archive.api.internal.JdbcArchiveRepositorySet;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;

/** Generation-pinned DuckLake read facade for the projection-owned archive. */
public final class DuckLakeHistoryArchiveBackend implements ArchiveBackend {
    private static final ArchiveCapabilities CAPABILITIES = new ArchiveCapabilities(
            true, false, true, false, false);

    private final ArchiveIdentity identity;
    private final DuckLakeArchiveConfig config;
    private final DuckDbManager manager;
    private final boolean ownsManager;
    private final ArchiveDirectoryLock directoryLock;
    private final StampedLock sessionGate = new StampedLock();
    private final Map<Long, LongAdder> activeSnapshots = new ConcurrentHashMap<>();
    private final AtomicReference<ArchiveHealth> health =
            new AtomicReference<>(ArchiveHealth.healthy());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean resourcesClosed = new AtomicBoolean();
    private final ArchiveRepositorySet repositories = new JdbcArchiveRepositorySet(
            session -> {
                if (!(session instanceof DuckLakeReadSession duckLake)) {
                    throw new IllegalArgumentException(
                            "read session does not belong to DuckLake backend");
                }
                return duckLake.connection();
            }, "history_lake.", null,
            "history_lake." + DuckLakeProjectionSchema.RECEIPTS_TABLE,
            "history_lake." + DuckLakeProjectionSchema.EPOCH_COVERAGE_TABLE);

    private DuckLakeTransactionLocator transactionLocator;

    public DuckLakeHistoryArchiveBackend(ArchiveIdentity identity, DuckLakeArchiveConfig config,
                                         DuckDbManager manager) {
        this(identity, config, manager, false);
    }

    private DuckLakeHistoryArchiveBackend(ArchiveIdentity identity, DuckLakeArchiveConfig config,
                                          DuckDbManager manager, boolean ownsManager) {
        this.identity = Objects.requireNonNull(identity, "identity");
        if (!identity.engine().equals("ducklake")) {
            throw new IllegalArgumentException("DuckLake backend requires engine=ducklake");
        }
        this.config = Objects.requireNonNull(config, "config");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.ownsManager = ownsManager;
        try {
            Files.createDirectories(config.dataPath());
        } catch (Exception e) {
            if (ownsManager) manager.close();
            throw new ArchiveStoreException("cannot create DuckLake data directory", e);
        }
        directoryLock = new ArchiveDirectoryLock(config.catalogPath());
        try (DuckDbLease lease = manager.acquire(
                DuckDbWorkload.BULK_CATCH_UP, config.acquireTimeout())) {
            DuckLakeSql.attach(lease.connection(), config, null, false);
            try {
                new DuckLakeInitializer(config).initializeProjection(lease.connection(), identity);
                DuckLakeProjectionSchema.initialize(lease.connection());
                transactionLocator = new DuckLakeTransactionLocator(config.catalogPath());
                transactionLocator.rebuildIfRequired(
                        lease.connection(), DuckLakeSql.currentSnapshot(lease.connection()));
            } finally {
                DuckLakeSql.detach(lease.connection());
            }
        } catch (Exception e) {
            directoryLock.close();
            if (ownsManager) manager.close();
            markUnhealthy("DuckLake read facade initialization failed", e);
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("DuckLake read facade initialization failed", e);
        }
    }

    public static DuckLakeHistoryArchiveBackend openReadOnly(
            ArchiveIdentity identity, DuckLakeArchiveConfig config,
            DuckDbManagerConfig managerConfig, PackagedDuckDbExtensionLoader extensions,
            ArchiveWaitPolicy waitPolicy) {
        return new DuckLakeHistoryArchiveBackend(identity, config,
                new DuckDbManager(managerConfig, extensions, waitPolicy), true);
    }

    public static DuckLakeHistoryArchiveBackend openReadOnly(
            ArchiveIdentity identity, DuckLakeArchiveConfig config,
            DuckDbManagerConfig managerConfig, PackagedDuckDbExtensionLoader extensions) {
        return openReadOnly(identity, config, managerConfig, extensions, config.waitPolicy());
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
        String slotPredicate = atOrBeforeSlot.isPresent() ? " AND last_slot<=?" : "";
        try (PreparedStatement query = read.connection().prepareStatement(
                "SELECT last_block,last_slot,last_block_hash FROM history_lake."
                        + DuckLakeProjectionSchema.RECEIPTS_TABLE
                        + " WHERE last_block BETWEEN ? AND ? AND last_slot IS NOT NULL"
                        + " AND last_block_hash IS NOT NULL" + slotPredicate
                        + " ORDER BY last_block DESC LIMIT 1")) {
            query.setLong(1, range.startInclusive());
            query.setLong(2, range.endInclusive());
            if (atOrBeforeSlot.isPresent()) query.setLong(3, atOrBeforeSlot.getAsLong());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                long block = row.getLong(1);
                long slot = row.getLong(2);
                byte[] hash = row.getBytes(3);
                return Optional.of(new ArchiveCommitBoundary(dataset,
                        ArchiveSchemas.schema(dataset).projectionVersion(),
                        new BlockRange(block, block),
                        new ArchiveRangeAnchor(slot, hash, slot, hash), read.generation()));
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
    public Optional<ArchiveRecord> findTransaction(ArchiveReadSession session, byte[] txHash) {
        if (!(session instanceof DuckLakeReadSession read)) {
            throw new IllegalArgumentException("read session does not belong to DuckLake backend");
        }
        OptionalLong block = transactionLocator.block(read.connection(), read.generation(), txHash);
        ArchiveQuery query = new ArchiveQuery(
                block.isPresent()
                        ? new BlockRange(block.getAsLong(), block.getAsLong())
                        : new BlockRange(0, Long.MAX_VALUE),
                Map.of("tx_hash", txHash), ArchivePageCursor.Order.ASC, 1, Optional.empty());
        Optional<ArchiveRecord> located = repositories.records(ArchiveDatasetId.TRANSACTION)
                .query(session, query).rows().stream().findFirst();
        if (located.isPresent() || block.isEmpty()) return located;
        ArchiveQuery fallback = new ArchiveQuery(new BlockRange(0, Long.MAX_VALUE),
                Map.of("tx_hash", txHash), ArchivePageCursor.Order.ASC, 1, Optional.empty());
        return repositories.records(ArchiveDatasetId.TRANSACTION).query(session, fallback)
                .rows().stream().findFirst();
    }

    @Override
    public ArchiveHealth health() {
        return health.get();
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

    private ArchiveCoverage coverage(Connection connection, long revision,
                                     ArchiveDatasetId dataset) throws SQLException {
        List<ArchiveRange> ranges = dataset.sourceKind() == SourceKind.BLOCK
                ? receiptRanges(connection)
                : epochRanges(connection, dataset);
        return new ArchiveCoverage(dataset,
                ArchiveSchemas.schema(dataset).projectionVersion(), revision, mergeAdjacent(ranges));
    }

    private List<ArchiveRange> receiptRanges(Connection connection) throws SQLException {
        List<ArchiveRange> ranges = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT first_block,last_block FROM history_lake."
                        + DuckLakeProjectionSchema.RECEIPTS_TABLE + " ORDER BY first_block");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) ranges.add(new BlockRange(rows.getLong(1), rows.getLong(2)));
        }
        return ranges;
    }

    private List<ArchiveRange> epochRanges(Connection connection,
                                           ArchiveDatasetId dataset) throws SQLException {
        List<ArchiveRange> ranges = new ArrayList<>();
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

    private static List<ArchiveRange> mergeAdjacent(List<ArchiveRange> ranges) {
        if (ranges.isEmpty()) return List.of();
        List<ArchiveRange> merged = new ArrayList<>();
        ArchiveRange current = ranges.getFirst();
        for (int index = 1; index < ranges.size(); index++) {
            ArchiveRange next = ranges.get(index);
            if (current.endInclusive() != Long.MAX_VALUE
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

    private static boolean isRequestCapacityTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof DuckDbCapacityTimeoutException) return true;
        }
        return false;
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("DuckLake backend is closed");
    }

    private void markDegraded(String detail, Throwable error) {
        if (!closed.get()) {
            health.set(new ArchiveHealth(ArchiveHealth.Status.DEGRADED,
                    detail + ": " + error.getMessage(), Instant.now()));
        }
    }

    private void markUnhealthy(String detail, Throwable error) {
        if (!closed.get()) {
            health.set(new ArchiveHealth(ArchiveHealth.Status.UNHEALTHY,
                    detail + ": " + error.getMessage(), Instant.now()));
        }
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

    private void finishCloseIfIdle() {
        if (!closed.get() || resourcesClosed.get()) return;
        long gateStamp = sessionGate.tryWriteLock();
        if (gateStamp == 0) return;
        try {
            if (resourcesClosed.compareAndSet(false, true)) {
                if (transactionLocator != null) transactionLocator.close();
                if (ownsManager) manager.close();
                directoryLock.close();
            }
        } finally {
            sessionGate.unlockWrite(gateStamp);
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
                try {
                    DuckLakeSql.detach(lease.connection());
                } catch (SQLException ignored) {
                    // Best effort during read cleanup.
                }
                lease.close();
            } finally {
                releaseGate.run();
            }
        }
    }
}
