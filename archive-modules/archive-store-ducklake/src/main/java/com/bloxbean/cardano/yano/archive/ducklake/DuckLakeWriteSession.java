package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveBatchCapacityException;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveRange;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWriteSession;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;

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
    private boolean committed;
    private boolean closed;
    private final List<DuckLakeTransactionLocator.Entry> transactionEntries = new ArrayList<>();
    private final List<DuckLakeAddressLocator.Entry> addressEntries = new ArrayList<>();
    private DuckLakeAddressLocator.Plan addressPlan = DuckLakeAddressLocator.Plan.empty();

    DuckLakeWriteSession(DuckLakeHistoryArchiveBackend backend, ArchiveJob job,
                         DuckDbLease lease, ArchiveReceipt replayReceipt) {
        this.backend = backend;
        this.job = job;
        this.lease = lease;
        this.connection = lease == null ? null : lease.connection();
        this.replayReceipt = replayReceipt;
        this.allowedTables = ArchiveSchemas.schema(job.dataset()).tables().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ArchiveTableSchema::physicalName, table -> table));
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
        if (row.table().equals("addresses")) {
            addressEntries.add(new DuckLakeAddressLocator.Entry(values));
        }
        if (replayReceipt != null) {
            rowCounts.merge(row.table(), 1L, Long::sum);
            updateDigest(row);
            return;
        }
        // The rebuildable SQLite address locator performs point collision
        // checks and suppresses already-known dimension rows at commit time.
        // Avoid staging every repeated address into DuckDB first.
        if (row.table().equals("addresses")) {
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
        Instant committedAt = Instant.now();
        String orderedDigest = HexFormat.of().formatHex(digest.digest());
        try {
            long predictedGeneration = Math.addExact(DuckLakeSql.currentSnapshot(connection), 1);
            prepareAddressStaging();
            flushPendingStagingBatches();
            verifyLogicalKeys();
            flushStaging();
            insertCommit(predictedGeneration, orderedDigest, committedAt);
            insertCounts();
            insertCoverage(predictedGeneration);
            try (Statement sql = connection.createStatement()) {
                sql.execute("COMMIT");
            }
            long actualGeneration = DuckLakeSql.currentSnapshot(connection);
            if (actualGeneration != predictedGeneration) {
                throw new ArchiveStoreException("DuckLake generation mismatch after commit: predicted="
                        + predictedGeneration + ", actual=" + actualGeneration);
            }
            ArchiveReceipt receipt = new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(),
                    job.projectionVersion(), job.range(), job.anchors(), actualGeneration,
                    rowCounts, orderedDigest, committedAt);
            backend.updateTransactionLocator(connection, actualGeneration, transactionEntries);
            backend.updateAddressLocator(actualGeneration, addressPlan);
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
        List<List<Object>> pending = pendingStagingRows.computeIfAbsent(tableName, ignored -> new ArrayList<>());
        pending.add(values);
        if (pending.size() >= STAGING_BATCH_SIZE) flushPendingStagingBatch(table, pending);
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
                if (table.equals("addresses")) {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM " + target + " WHERE address_key=?")) {
                        for (DuckLakeAddressLocator.PlannedEntry planned : addressPlan.accepted()) {
                            if (!planned.replace()) continue;
                            delete.setBytes(1, planned.entry().key());
                            delete.executeUpdate();
                        }
                    }
                    sql.execute("INSERT INTO " + target + " SELECT * FROM " + staging);
                } else {
                    sql.execute("INSERT INTO " + target + " SELECT * FROM " + staging);
                }
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
                    if (duplicateCount != 0 && !table.equals("addresses")) {
                        throw new ArchiveStoreException("duplicate logical primary key in job for " + table);
                    }

                    String target = "history_lake." + DuckLakeSql.name(table);
                    if (table.equals("addresses")) {
                        // The address locator already checked durable and
                        // within-job immutable attributes before staging only
                        // accepted new/earlier rows.
                    } else {
                        try (var result = sql.executeQuery("SELECT count(*) FROM " + staging + " s JOIN " + target
                                + " t ON " + keyJoin(schema, "s", "t")
                                + targetRangePredicate(schema, job.range(), staging, "t"))) {
                            result.next();
                            if (result.getLong(1) != 0) {
                                throw new ArchiveStoreException("logical primary key already exists for " + table);
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new SQLException("logical-key verification failed for " + table + ": "
                            + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
                }
            }
        }
    }

    private void prepareAddressStaging() throws SQLException {
        addressPlan = backend.planAddresses(connection, addressEntries);
        if (addressPlan.accepted().isEmpty()) return;
        ArchiveTableSchema table = allowedTables.get("addresses");
        createStaging(table);
        for (DuckLakeAddressLocator.PlannedEntry planned : addressPlan.accepted()) {
            stageRow(table, planned.entry().values());
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
        if (connection != null && !committed) {
            try (Statement sql = connection.createStatement()) { sql.execute("ROLLBACK"); }
            catch (SQLException ignored) { }
        }
        if (connection != null) {
            try { DuckLakeSql.detach(connection); } catch (SQLException ignored) { }
        }
        if (lease != null) lease.close();
        backend.releaseWriter();
    }
}
