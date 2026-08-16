package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class SqliteWriteSession implements ArchiveWriteSession {
    private final SqliteHistoryArchiveBackend backend;
    private final ArchiveJob job;
    private final Connection connection;
    private final ArchiveReceipt replayReceipt;
    private final long generation;
    private final Map<String, ArchiveTableSchema> allowedTables;
    private final Map<String, PreparedStatement> inserts = new HashMap<>();
    private final Map<String, Long> rowCounts = new LinkedHashMap<>();
    private final Map<String, Set<List<Object>>> logicalKeys = new HashMap<>();
    private final MessageDigest digest;
    private boolean committed;
    private boolean closed;

    SqliteWriteSession(SqliteHistoryArchiveBackend backend, ArchiveJob job, Connection connection,
                       ArchiveReceipt replayReceipt, long generation) {
        this.backend = backend;
        this.job = job;
        this.connection = connection;
        this.replayReceipt = replayReceipt;
        this.generation = generation;
        this.allowedTables = ArchiveSchemas.schema(job.dataset()).tables().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ArchiveTableSchema::physicalName, table -> table));
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ArchiveStoreException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public void append(ArchiveRow row) {
        requireOpen();
        Objects.requireNonNull(row, "row");
        ArchiveTableSchema table = allowedTables.get(row.table());
        if (table == null) throw new ArchiveStoreException("table " + row.table() + " is not part of " + job.dataset());
        if (row.values().size() != table.columns().size()) {
            throw new ArchiveStoreException("row value count does not match " + row.table());
        }
        int jobColumn = columnIndex(table, "archive_job_id");
        if (jobColumn >= 0 && !job.jobId().equals(row.values().get(jobColumn))) {
            throw new ArchiveStoreException("row archive_job_id does not match active job for " + row.table());
        }
        List<Object> key = logicalKey(table, row);
        if (!logicalKeys.computeIfAbsent(row.table(), ignored -> new HashSet<>()).add(key)) {
            throw new ArchiveStoreException("duplicate logical primary key in job for " + row.table());
        }

        if (replayReceipt == null) {
            try {
                PreparedStatement insert = inserts.computeIfAbsent(row.table(), ignored -> prepareInsert(table));
                SqliteArchiveSql.bind(insert, table, row);
                insert.executeUpdate();
            } catch (SQLException e) {
                throw new ArchiveStoreException("failed to append SQLite row for " + row.table(), e);
            }
        }
        rowCounts.merge(row.table(), 1L, Long::sum);
        updateDigest(row);
    }

    @Override
    public ArchiveReceipt commit() {
        requireOpen();
        if (replayReceipt != null) {
            String retryDigest = HexFormat.of().formatHex(digest.digest());
            if (!replayReceipt.rowCounts().equals(rowCounts)
                    || !replayReceipt.orderedDigest().equals(retryDigest)) {
                close();
                throw new ArchiveStoreException("committed job retry has different rows: " + job.jobId());
            }
            committed = true;
            close();
            return replayReceipt;
        }

        Instant committedAt = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        String orderedDigest = HexFormat.of().formatHex(digest.digest());
        try {
            try (PreparedStatement count = connection.prepareStatement(
                    "INSERT INTO archive_commit_counts(job_id, table_name, row_count) VALUES (?, ?, ?)")) {
                for (var entry : rowCounts.entrySet()) {
                    count.setString(1, job.jobId().toString());
                    count.setString(2, entry.getKey());
                    count.setLong(3, entry.getValue());
                    count.executeUpdate();
                }
            }
            try (PreparedStatement coverage = connection.prepareStatement(
                    "INSERT INTO archive_coverage VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                coverage.setString(1, job.jobId().toString());
                coverage.setString(2, job.dataset().name());
                coverage.setInt(3, job.projectionVersion());
                coverage.setString(4, job.range().sourceKind().name());
                coverage.setLong(5, job.range().startInclusive());
                coverage.setLong(6, job.range().endInclusive());
                coverage.setLong(7, generation);
                coverage.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE archive_commits SET ordered_digest=?, committed_at=? WHERE job_id=?")) {
                update.setString(1, orderedDigest);
                update.setLong(2, committedAt.toEpochMilli());
                update.setString(3, job.jobId().toString());
                if (update.executeUpdate() != 1) throw new ArchiveStoreException("missing SQLite job header");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE archive_generation SET generation=? WHERE singleton=1 AND generation=?")) {
                update.setLong(1, generation);
                update.setLong(2, generation - 1);
                if (update.executeUpdate() != 1) throw new ArchiveStoreException("SQLite generation changed unexpectedly");
            }
            connection.commit();
            committed = true;
            ArchiveReceipt receipt = new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(),
                    job.projectionVersion(), job.range(), job.anchors(), generation,
                    rowCounts, orderedDigest, committedAt);
            close();
            return receipt;
        } catch (SQLException e) {
            throw new ArchiveStoreException("failed to commit SQLite archive job " + job.jobId(), e);
        } finally {
            if (!committed) close();
        }
    }

    private PreparedStatement prepareInsert(ArchiveTableSchema table) {
        try {
            return connection.prepareStatement(SqliteArchiveSql.insertSql(table));
        } catch (SQLException e) {
            throw new ArchiveStoreException("failed to prepare SQLite insert for " + table.physicalName(), e);
        }
    }

    private List<Object> logicalKey(ArchiveTableSchema table, ArchiveRow row) {
        List<Object> key = new ArrayList<>(table.primaryKey().size());
        for (String column : table.primaryKey()) {
            Object value = row.values().get(columnIndex(table, column));
            key.add(value instanceof byte[] bytes ? ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer() : value);
        }
        return java.util.Collections.unmodifiableList(key);
    }

    private int columnIndex(ArchiveTableSchema table, String name) {
        for (int index = 0; index < table.columns().size(); index++) {
            if (table.columns().get(index).name().equals(name)) return index;
        }
        return -1;
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

    private void requireOpen() {
        if (closed) throw new IllegalStateException("archive write session is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (PreparedStatement insert : inserts.values()) {
            try { insert.close(); } catch (SQLException ignored) { }
        }
        if (connection != null) {
            if (!committed) {
                try { connection.rollback(); } catch (SQLException ignored) { }
            }
            try { connection.close(); } catch (SQLException ignored) { }
        }
        backend.releaseWriter();
    }
}
