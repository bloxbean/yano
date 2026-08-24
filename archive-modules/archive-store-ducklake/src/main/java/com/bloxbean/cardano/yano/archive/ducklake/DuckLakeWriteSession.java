package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveBatchCapacityException;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveRange;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWriteSession;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveColumn;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveValueType;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class DuckLakeWriteSession implements ArchiveWriteSession {
    // Bound JDBC-side buffering while replacing one native execute call per row
    // with vector-sized staging batches. The worker's independent row limit
    // remains the archive-job memory bound.
    static final int STAGING_BATCH_SIZE = 256;

    private final DuckLakeHistoryArchiveBackend backend;
    private final ArchiveJob job;
    private final DuckDbLease lease;
    private final Connection connection;
    private final Map<String, ArchiveTableSchema> allowedTables;
    private final Set<String> stagingTables = new LinkedHashSet<>();
    private final Map<String, List<List<Object>>> pendingStagingRows = new LinkedHashMap<>();
    private final Map<String, Long> rowCounts = new LinkedHashMap<>();
    private final MessageDigest digest;
    private final ArchiveReceipt replayReceipt;
    // This session owns its writer permit for its whole life, including when it
    // is closed on a different executor than the one that opened it.
    private final long writerTicket;
    private boolean committed;
    private boolean closed;
    private final List<DuckLakeTransactionLocator.Entry> transactionEntries = new ArrayList<>();
    private final DuckLakeWriteStageTimings timings = new DuckLakeWriteStageTimings();
    // One lazily created Appender per staging table. UTXO_HISTORY interleaves five
    // tables in a single row stream, so several stay open for the whole session.
    private final Map<String, DuckDBAppender> appenders = new LinkedHashMap<>();
    private final boolean appenderMode;
    // Appends arrive contiguously from the worker, which materialises every row
    // before opening the session. Timing the window rather than each call keeps
    // per-row cost at zero while still attributing the stage accurately.
    private long appendWindowStart;

    private static final System.Logger LOG = System.getLogger(DuckLakeWriteSession.class.getName());

    /**
     * Temporary rollback switch for the ADR-038 Phase 2 append path. Set
     * {@code -Dyano.archive.ducklake.append-mode=legacy} to restore the
     * prepared-statement batches without rebuilding. Remove once the Appender path
     * has been validated in production.
     */
    static final String APPEND_MODE_PROPERTY = "yano.archive.ducklake.append-mode";

    static boolean appenderModeEnabled() {
        return !"legacy".equalsIgnoreCase(System.getProperty(APPEND_MODE_PROPERTY, "appender").trim());
    }

    /** Per-commit stage attribution; see ADR-038 Phase 0. */
    DuckLakeWriteStageTimings timings() {
        return timings;
    }

    DuckLakeWriteSession(DuckLakeHistoryArchiveBackend backend, ArchiveJob job,
                         DuckDbLease lease, ArchiveReceipt replayReceipt, long writerTicket) {
        this.backend = backend;
        this.job = job;
        this.lease = lease;
        this.writerTicket = writerTicket;
        this.connection = lease == null ? null : lease.connection();
        this.replayReceipt = replayReceipt;
        this.allowedTables = ArchiveSchemas.schema(job.dataset()).tables().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ArchiveTableSchema::physicalName, table -> table));
        // Replay verification is write-free, so it never opens an Appender.
        this.appenderMode = replayReceipt == null && appenderModeEnabled();
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
            if (connection != null) {
                try (Statement sql = connection.createStatement()) {
                    sql.execute("BEGIN TRANSACTION");
                }
            }
        } catch (NoSuchAlgorithmException | SQLException e) {
            // begin() still owns the lease and writer permit until construction succeeds.
            throw new ArchiveStoreException("cannot start DuckLake archive transaction", e);
        }
    }

    @Override
    public void append(ArchiveRow row) {
        requireOpen();
        Objects.requireNonNull(row, "row");
        ArchiveTableSchema table = allowedTables.get(row.table());
        if (table == null) throw new ArchiveStoreException("table " + row.table() + " is not part of " + job.dataset());
        var values = row.values();
        if (values.size() != table.columns().size()) {
            throw new ArchiveStoreException("row value count does not match " + row.table());
        }
        int jobColumn = java.util.stream.IntStream.range(0, table.columns().size())
                .filter(index -> table.columns().get(index).name().equals("archive_job_id"))
                .findFirst().orElse(-1);
        if (jobColumn >= 0 && !job.jobId().equals(values.get(jobColumn))) {
            throw new ArchiveStoreException("row archive_job_id does not match active job for " + row.table());
        }
        if (job.dataset() == com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.TRANSACTION
                && row.table().equals("chain_transaction")) {
            transactionEntries.add(new DuckLakeTransactionLocator.Entry((byte[]) values.get(0),
                    ((Number) values.get(2)).longValue(), job.jobId()));
        }
        if (replayReceipt != null) {
            rowCounts.merge(row.table(), 1L, Long::sum);
            updateDigest(row);
            return;
        }
        try {
            stageRow(table, values);
            rowCounts.merge(row.table(), 1L, Long::sum);
            updateDigest(row);
        } catch (SQLException e) {
            if (isCapacityFailure(e)) {
                throw new ArchiveBatchCapacityException("DuckLake staging exceeded its configured budget", e);
            }
            throw new ArchiveStoreException("failed to stage DuckLake row for " + row.table(), e);
        }
    }

    @Override
    public ArchiveReceipt commit() {
        requireOpen();
        if (replayReceipt != null) {
            String replayDigest = HexFormat.of().formatHex(digest.digest());
            if (!replayReceipt.rowCounts().equals(rowCounts)
                    || !replayReceipt.orderedDigest().equals(replayDigest)) {
                close();
                throw new ArchiveStoreException("committed job retry has different rows: " + job.jobId());
            }
            committed = true;
            close();
            return replayReceipt;
        }
        // DuckDB TIMESTAMP persists microseconds. Return the same precision that a retry reads
        // back, otherwise Linux clocks with nanosecond resolution make the first and replayed
        // receipts unequal even though they describe the same durable commit.
        Instant committedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String orderedDigest = HexFormat.of().formatHex(digest.digest());
        try {
            long predictedGeneration = Math.addExact(DuckLakeSql.currentSnapshot(connection), 1);
            // Rows must all be in staging before logical-key verification runs.
            flushPendingStagingBatches();
            finishAppends();
            if (appendWindowStart != 0) timings.addAppend(System.nanoTime() - appendWindowStart);
            long mark = System.nanoTime();
            verifyLogicalKeys();
            timings.addVerify(System.nanoTime() - mark);
            mark = System.nanoTime();
            flushStaging();
            timings.addCopy(System.nanoTime() - mark);
            mark = System.nanoTime();
            insertCommit(predictedGeneration, orderedDigest, committedAt);
            insertCounts();
            insertCoverage(predictedGeneration);
            timings.addMetadata(System.nanoTime() - mark);
            mark = System.nanoTime();
            try (Statement sql = connection.createStatement()) {
                sql.execute("COMMIT");
            }
            timings.addCommit(System.nanoTime() - mark);
            long actualGeneration = DuckLakeSql.currentSnapshot(connection);
            if (actualGeneration != predictedGeneration) {
                throw new ArchiveStoreException("DuckLake generation mismatch after commit: predicted="
                        + predictedGeneration + ", actual=" + actualGeneration);
            }
            ArchiveReceipt receipt = new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(),
                    job.projectionVersion(), job.range(), job.anchors(), actualGeneration,
                    rowCounts, orderedDigest, committedAt);
            long locatorMark = System.nanoTime();
            backend.updateTransactionLocator(connection, actualGeneration, transactionEntries);
            timings.addLocator(System.nanoTime() - locatorMark);
            timings.rows(rowCounts.values().stream().mapToLong(Long::longValue).sum());
            // One line per commit (tens per hour), deliberately INFO so ADR-038
            // validation can observe stage attribution without enabling broad
            // DEBUG logging on a production deployment.
            LOG.log(System.Logger.Level.INFO, "Archive commit stages {0} mode={1} {2}",
                    job.dataset(), appenderMode ? "appender" : "legacy", timings.summary());
            committed = true;
            close();
            return receipt;
        } catch (SQLException e) {
            if (isCapacityFailure(e)) {
                throw new ArchiveBatchCapacityException(
                        "DuckLake commit exceeded its configured budget for job " + job.jobId(), e);
            }
            throw new ArchiveStoreException("failed to commit DuckLake archive job " + job.jobId(), e);
        } catch (ArithmeticException e) {
            throw new ArchiveStoreException("failed to commit DuckLake archive job " + job.jobId(), e);
        } finally {
            if (!committed) close();
        }
    }

    private void createStaging(ArchiveTableSchema table) {
        String staging = stagingName(table.physicalName());
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TEMP TABLE " + staging + " AS SELECT * FROM history_lake."
                    + DuckLakeSql.name(table.physicalName()) + " LIMIT 0");
        } catch (SQLException e) {
            throw new ArchiveStoreException("failed to create DuckLake staging table " + staging, e);
        }
        stagingTables.add(table.physicalName());
    }

    private void stageRow(ArchiveTableSchema table, List<Object> values) throws SQLException {
        String tableName = table.physicalName();
        if (!stagingTables.contains(tableName)) createStaging(table);
        if (appendWindowStart == 0) appendWindowStart = System.nanoTime();
        if (appenderMode) {
            appendViaAppender(table, values);
            return;
        }
        List<List<Object>> pending = pendingStagingRows.computeIfAbsent(tableName, ignored -> new ArrayList<>());
        pending.add(values);
        if (pending.size() >= STAGING_BATCH_SIZE) {
            flushPendingStagingBatch(table, pending);
        }
    }

    /**
     * ADR-038 Phase 2, shape (b): rows enter the native staging table through a
     * DuckDB Appender instead of 256-row prepared-statement batches. Staging,
     * {@link #verifyLogicalKeys()} and the {@code INSERT ... SELECT} publication
     * are unchanged, so the write contract is untouched.
     */
    private void appendViaAppender(ArchiveTableSchema table, List<Object> values) throws SQLException {
        DuckDBAppender appender = appenders.get(table.physicalName());
        if (appender == null) {
            appender = ((DuckDBConnection) connection)
                    .createAppender("temp", "main", stagingName(table.physicalName()));
            appenders.put(table.physicalName(), appender);
        }
        appender.beginRow();
        List<ArchiveColumn> columns = table.columns();
        for (int index = 0; index < columns.size(); index++) {
            appendValue(appender, columns.get(index).type(), values.get(index));
        }
        appender.endRow();
    }

    private static void appendValue(DuckDBAppender appender, ArchiveValueType type, Object value)
            throws SQLException {
        if (value == null) {
            appender.appendNull();
            return;
        }
        switch (type) {
            case BINARY -> appender.append((byte[]) value);
            case TEXT -> appender.append((String) value);
            case BOOLEAN -> appender.append((Boolean) value);
            case INT32 -> appender.append(((Number) value).intValue());
            case INT64 -> appender.append(((Number) value).longValue());
            case DECIMAL_38 -> appender.append(value instanceof BigDecimal decimal
                    ? decimal : new BigDecimal(value instanceof BigInteger big ? big : new BigInteger(value.toString())));
            case UUID -> appender.append((UUID) value);
        }
    }

    /**
     * Flushes and closes every Appender so all rows are visible to
     * {@link #verifyLogicalKeys()} before publication. Closing is idempotent; the
     * map is cleared so {@link #close()} does not double-close.
     */
    private void finishAppends() throws SQLException {
        SQLException failure = null;
        for (DuckDBAppender appender : appenders.values()) {
            try {
                appender.flush();
                appender.close();
            } catch (SQLException e) {
                if (failure == null) failure = e;
            }
        }
        appenders.clear();
        if (failure != null) throw failure;
    }

    /** Best-effort Appender disposal on the failure path, before ROLLBACK. */
    private void discardAppenders() {
        for (DuckDBAppender appender : appenders.values()) {
            try { appender.close(); } catch (SQLException ignored) { }
        }
        appenders.clear();
    }

    private void flushPendingStagingBatches() throws SQLException {
        for (var entry : pendingStagingRows.entrySet()) {
            flushPendingStagingBatch(allowedTables.get(entry.getKey()), entry.getValue());
        }
    }

    private void flushPendingStagingBatch(ArchiveTableSchema table, List<List<Object>> pending) throws SQLException {
        if (pending.isEmpty()) return;
        String rowPlaceholders = '(' + table.columns().stream().map(ignored -> "?")
                .reduce((left, right) -> left + ", " + right).orElseThrow() + ')';
        String valuesPlaceholders = java.util.stream.IntStream.range(0, pending.size())
                .mapToObj(ignored -> rowPlaceholders)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO "
                + stagingName(table.physicalName()) + " VALUES " + valuesPlaceholders)) {
            int parameter = 1;
            for (List<Object> row : pending) {
                for (Object value : row) insert.setObject(parameter++, value);
            }
            insert.executeUpdate();
        }
        pending.clear();
    }

    private void flushStaging() throws SQLException {
        try (Statement sql = connection.createStatement()) {
            for (String table : stagingTables) {
                ArchiveTableSchema schema = allowedTables.get(table);
                String target = "history_lake." + DuckLakeSql.name(table);
                String staging = stagingName(table);
                sql.execute("INSERT INTO " + target + " SELECT * FROM " + staging);
            }
        }
    }

    private void verifyLogicalKeys() throws SQLException {
        try (Statement sql = connection.createStatement()) {
            for (String table : stagingTables) {
                ArchiveTableSchema schema = allowedTables.get(table);
                String staging = stagingName(table);
                String keys = schema.primaryKey().stream().map(DuckLakeSql::name)
                        .reduce((left, right) -> left + ", " + right).orElseThrow();
                try {
                    long duplicateCount;
                    try (var result = sql.executeQuery("SELECT count(*) FROM (SELECT " + keys + " FROM "
                            + staging + " GROUP BY " + keys + " HAVING count(*) > 1)")) {
                        result.next();
                        duplicateCount = result.getLong(1);
                    }
                    if (duplicateCount != 0) {
                        throw new ArchiveStoreException("duplicate logical primary key in job for " + table);
                    }

                    String target = "history_lake." + DuckLakeSql.name(table);
                    try (var result = sql.executeQuery("SELECT count(*) FROM " + staging + " s JOIN " + target
                            + " t ON " + keyJoin(schema, "s", "t")
                            + targetRangePredicate(schema, job.range(), staging, "t"))) {
                        result.next();
                        if (result.getLong(1) != 0) {
                            throw new ArchiveStoreException("logical primary key already exists for " + table);
                        }
                    }
                } catch (SQLException e) {
                    throw new SQLException("logical-key verification failed for " + table + ": "
                            + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                }
            }
        }
    }

    static String targetRangePredicate(ArchiveTableSchema schema, ArchiveRange range,
                                       String stagingTable, String targetAlias) {
        boolean hasEpoch = schema.columns().stream().anyMatch(column -> column.name().equals("epoch"));
        boolean hasBlock = schema.columns().stream().anyMatch(column -> column.name().equals("block_number"));
        StringBuilder predicate = new StringBuilder();
        if (hasEpoch) {
            predicate.append(" AND ").append(targetAlias).append(".epoch IN (SELECT DISTINCT epoch FROM ")
                    .append(stagingTable).append(" WHERE epoch IS NOT NULL)");
        }
        if (range.sourceKind() == com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK && hasBlock) {
            predicate.append(" AND ").append(targetAlias).append(".block_number BETWEEN ")
                    .append(range.startInclusive()).append(" AND ").append(range.endInclusive());
        } else if (range.sourceKind() == com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH && hasEpoch) {
            predicate.append(" AND ").append(targetAlias).append(".epoch BETWEEN ")
                    .append(range.startInclusive()).append(" AND ").append(range.endInclusive());
        }
        return predicate.toString();
    }

    private String keyJoin(ArchiveTableSchema schema, String left, String right) {
        return schema.primaryKey().stream()
                .map(key -> left + '.' + DuckLakeSql.name(key) + " IS NOT DISTINCT FROM "
                        + right + '.' + DuckLakeSql.name(key))
                .reduce((a, b) -> a + " AND " + b).orElseThrow();
    }

    static boolean isCapacityFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("Out of Memory Error")
                    || message.contains("failed to allocate data of size")
                    || message.contains("max_temp_directory_size"))) {
                return true;
            }
        }
        return false;
    }

    private void insertCommit(long generation, String orderedDigest, Instant committedAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history_lake.archive_commits VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            insert.setObject(1, job.jobId());
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
            insert.setString(13, orderedDigest);
            insert.setTimestamp(14, Timestamp.from(committedAt));
            insert.executeUpdate();
        }
    }

    private void insertCounts() throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history_lake.archive_commit_counts VALUES (?, ?, ?)")) {
            for (var entry : rowCounts.entrySet()) {
                insert.setObject(1, job.jobId());
                insert.setString(2, entry.getKey());
                insert.setLong(3, entry.getValue());
                insert.executeUpdate();
            }
        }
    }

    private void insertCoverage(long generation) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history_lake.archive_coverage VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, job.jobId());
            insert.setString(2, job.dataset().name());
            insert.setInt(3, job.projectionVersion());
            insert.setString(4, job.range().sourceKind().name());
            insert.setLong(5, job.range().startInclusive());
            insert.setLong(6, job.range().endInclusive());
            insert.setLong(7, generation);
            insert.executeUpdate();
        }
    }

    private void updateDigest(ArchiveRow row) {
        digest.update(row.table().getBytes(StandardCharsets.UTF_8));
        for (Object value : row.values()) {
            if (value == null) {
                digest.update((byte) 0);
            } else if (value instanceof byte[] bytes) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            } else {
                byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
                digest.update(encoded);
            }
        }
    }

    private String stagingName(String table) {
        return DuckLakeSql.name("stage_" + table);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("archive write session is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Appenders hold native handles against the staging tables and must be
        // released before the transaction is rolled back and the catalog detached.
        discardAppenders();
        if (connection != null && !committed) {
            try (Statement sql = connection.createStatement()) { sql.execute("ROLLBACK"); }
            catch (SQLException ignored) { }
        }
        if (connection != null) {
            try { DuckLakeSql.detach(connection); } catch (SQLException ignored) { }
        }
        if (lease != null) lease.close();
        backend.releaseWriter(writerTicket);
    }
}
