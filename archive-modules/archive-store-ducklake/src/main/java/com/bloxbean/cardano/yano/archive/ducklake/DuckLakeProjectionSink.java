package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBatchCapacityException;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveColumn;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveValueType;
import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionMaintenance;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceipt;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionReceiptMismatchException;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionRowBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkException;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkHealth;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * DuckLake as the ADR-039 primary projection sink.
 *
 * <p>One batch is one DuckLake transaction: every required table plus the durable
 * receipt become visible together. That is what turns at-least-once delivery into
 * exactly-once effect — a crash between the sink commit and outbox acknowledgement
 * replays the same deterministic batch, finds the matching receipt, and acknowledges
 * without writing anything twice.
 *
 * <p>Deliberately absent: the SQLite transaction locator. ADR-038 measured it holding
 * shared archive capacity for seconds to minutes, and it is a rebuildable query
 * accelerator, not authoritative archive data. Nothing here gates canonical archive
 * progress on it (ADR-039 §15).
 *
 * <p>This sink does no ledger resolution. It receives rows already derived by shared
 * archive-core code and only translates and batches them, which is why this module
 * depends on {@code archive-api} alone.
 */
public final class DuckLakeProjectionSink implements ProjectionSink {

    private final DuckDbManager manager;
    private final DuckLakeArchiveConfig config;
    private final Map<String, ArchiveTableSchema> tables;

    private volatile ProjectionIdentity identity;
    private volatile ProjectionSinkHealth health = ProjectionSinkHealth.ready();

    public DuckLakeProjectionSink(DuckDbManager manager, DuckLakeArchiveConfig config) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.config = Objects.requireNonNull(config, "config");
        this.tables = DuckLakeSql.tables();
    }

    @Override
    public String engine() {
        return "ducklake";
    }

    @Override
    public void initialize(ProjectionIdentity expected) {
        Objects.requireNonNull(expected, "expected");
        // Every required section must map to tables this catalog actually has. A sink that
        // cannot serve a required section fails here rather than acknowledging envelopes
        // that quietly omit it.
        for (ProjectionSectionType section : expected.requiredSections()) {
            for (ArchiveTableSchema table : com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas
                    .schema(section.dataset()).tables()) {
                if (!tables.containsKey(table.physicalName())) {
                    throw new ProjectionSinkException("DuckLake catalog has no table " + table.physicalName()
                            + " required by projection section " + section.wireName());
                }
            }
        }

        try (DuckDbLease lease = manager.acquire(DuckDbWorkload.BULK_CATCH_UP, config.acquireTimeout())) {
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, false);
            try {
                DuckLakeProjectionSchema.initialize(connection);
                Optional<String> stored = storedFingerprint(connection);
                if (stored.isPresent() && !stored.get().equals(expected.fingerprint())) {
                    throw new ProjectionSinkException("DuckLake archive was written by projection identity "
                            + stored.get() + " but this node is configured for " + expected.fingerprint());
                }
                if (stored.isEmpty()) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO history_lake.projection_identity VALUES (?, ?)")) {
                        insert.setString(1, expected.fingerprint());
                        insert.setTimestamp(2, Timestamp.from(Instant.now()));
                        insert.executeUpdate();
                    }
                }
            } finally {
                DuckLakeSql.detach(connection);
            }
        } catch (SQLException e) {
            throw new ProjectionSinkException("failed to initialize DuckLake projection sink", e);
        }
        this.identity = expected;
    }

    @Override
    public ProjectionCoordinate coordinate() {
        // The greatest contiguous committed block is derived from receipts, not from the
        // maximum row present: a partially visible range must never look committed.
        try (DuckDbLease lease = manager.acquire(DuckDbWorkload.STEADY, config.acquireTimeout())) {
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, true);
            try (Statement sql = connection.createStatement();
                 ResultSet rs = sql.executeQuery(
                         "SELECT first_block, last_block FROM history_lake.projection_receipts ORDER BY first_block")) {
                long contiguousThrough = -1;
                while (rs.next()) {
                    long first = rs.getLong(1);
                    long last = rs.getLong(2);
                    if (first != contiguousThrough + 1) break;
                    contiguousThrough = last;
                }
                return contiguousThrough < 0 ? ProjectionCoordinate.NONE
                        : new ProjectionCoordinate(contiguousThrough, contiguousThrough,
                                new byte[]{1}, "committed");
            } finally {
                DuckLakeSql.detach(connection);
            }
        } catch (SQLException e) {
            throw new ProjectionSinkException("failed to read DuckLake projection coordinate", e);
        }
    }

    @Override
    public Optional<ProjectionReceipt> receiptFor(long firstBlock) {
        try (DuckDbLease lease = manager.acquire(DuckDbWorkload.STEADY, config.acquireTimeout())) {
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, true);
            try {
                return readReceipt(connection, firstBlock);
            } finally {
                DuckLakeSql.detach(connection);
            }
        } catch (SQLException e) {
            throw new ProjectionSinkException("failed to read DuckLake projection receipt", e);
        }
    }

    @Override
    public ProjectionReceipt append(ProjectionRowBatch batch, ArchiveArtifactReader artifacts) {
        Objects.requireNonNull(batch, "batch");
        if (identity == null) throw new ProjectionSinkException("DuckLake projection sink is not initialized");
        if (!identity.matches(batch.identity())) {
            throw new ProjectionSinkException("batch identity " + batch.identity().fingerprint()
                    + " does not match the sink identity " + identity.fingerprint());
        }

        try (DuckDbLease lease = manager.acquire(DuckDbWorkload.BULK_CATCH_UP, config.acquireTimeout())) {
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, false);
            try {
                Optional<ProjectionReceipt> replay = readReceipt(connection, batch.firstBlock());
                if (replay.isPresent()) {
                    if (!replay.get().matches(batch)) {
                        throw new ProjectionReceiptMismatchException(
                                "a DuckLake receipt already covers block " + batch.firstBlock()
                                        + " but describes a different job");
                    }
                    return replay.get();
                }
                return commitBatch(connection, batch);
            } finally {
                DuckLakeSql.detach(connection);
            }
        } catch (SQLException e) {
            health = ProjectionSinkHealth.unavailable(e.toString());
            throw new ProjectionSinkException("DuckLake projection commit failed for blocks "
                    + batch.firstBlock() + ".." + batch.lastBlock(), e);
        }
    }

    private ProjectionReceipt commitBatch(Connection connection, ProjectionRowBatch batch) throws SQLException {
        Map<String, Long> rowCounts = new LinkedHashMap<>();
        Set<String> staged = new LinkedHashSet<>();
        Map<String, DuckDBAppender> appenders = new LinkedHashMap<>();
        Instant committedAt = Instant.now();

        try (Statement sql = connection.createStatement()) {
            sql.execute("BEGIN TRANSACTION");
        }
        try {
            for (ArchiveRow row : batch.rows()) {
                ArchiveTableSchema table = tables.get(row.table());
                if (table == null) {
                    throw new ArchiveStoreException("unknown archive table in projection batch: " + row.table());
                }
                if (staged.add(table.physicalName())) createStaging(connection, table);
                appendRow(connection, appenders, table, row.values());
                rowCounts.merge(table.physicalName(), 1L, Long::sum);
            }

            for (DuckDBAppender appender : appenders.values()) {
                appender.flush();
                appender.close();
            }
            appenders.clear();

            for (String table : staged) {
                try (Statement sql = connection.createStatement()) {
                    sql.execute("INSERT INTO history_lake." + DuckLakeSql.name(table)
                            + " SELECT * FROM " + stagingName(table));
                }
            }

            insertReceipt(connection, batch, rowCounts, committedAt);

            try (Statement sql = connection.createStatement()) {
                sql.execute("COMMIT");
            }
            health = ProjectionSinkHealth.ready();
            return ProjectionReceipt.of(batch, rowCounts, committedAt);
        } catch (SQLException | RuntimeException failure) {
            for (DuckDBAppender appender : appenders.values()) {
                try {
                    appender.close();
                } catch (Exception ignored) {
                    // The transaction is being rolled back; a close failure adds nothing.
                }
            }
            try (Statement sql = connection.createStatement()) {
                sql.execute("ROLLBACK");
            } catch (SQLException ignored) {
                // Preserve the original failure; the connection is discarded either way.
            }
            if (failure instanceof SQLException sqlFailure && isCapacityFailure(sqlFailure)) {
                throw new ArchiveBatchCapacityException("DuckLake projection commit exceeded its budget for blocks "
                        + batch.firstBlock() + ".." + batch.lastBlock(), sqlFailure);
            }
            throw failure;
        }
    }

    private void insertReceipt(Connection connection, ProjectionRowBatch batch,
                               Map<String, Long> rowCounts, Instant committedAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO history_lake.projection_receipts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setLong(1, batch.firstBlock());
            insert.setLong(2, batch.lastBlock());
            insert.setLong(3, batch.blockCount());
            insert.setString(4, batch.identity().fingerprint());
            insert.setString(5, batch.firstEnvelopeId());
            insert.setString(6, batch.lastEnvelopeId());
            insert.setString(7, batch.orderedDigest());
            insert.setString(8, encodeCounts(rowCounts));
            insert.setTimestamp(9, Timestamp.from(committedAt));
            insert.executeUpdate();
        }
    }

    private Optional<ProjectionReceipt> readReceipt(Connection connection, long firstBlock) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT first_block, last_block, block_count, identity_fingerprint, first_envelope_id, "
                        + "last_envelope_id, ordered_digest, row_counts, committed_at "
                        + "FROM history_lake.projection_receipts WHERE first_block = ?")) {
            select.setLong(1, firstBlock);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ProjectionReceipt(rs.getString(4), rs.getLong(1), rs.getLong(2),
                        rs.getString(5), rs.getString(6), rs.getLong(3), decodeCounts(rs.getString(8)),
                        rs.getString(7), rs.getTimestamp(9).toInstant()));
            }
        }
    }

    private void createStaging(Connection connection, ArchiveTableSchema table) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TEMP TABLE " + stagingName(table.physicalName())
                    + " AS SELECT * FROM history_lake." + DuckLakeSql.name(table.physicalName()) + " LIMIT 0");
        }
    }

    private static void appendRow(Connection connection, Map<String, DuckDBAppender> appenders,
                                  ArchiveTableSchema table, List<Object> values) throws SQLException {
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
            case DECIMAL_38 -> appender.append(value instanceof BigDecimal decimal ? decimal
                    : new BigDecimal(value instanceof BigInteger big ? big : new BigInteger(value.toString())));
            case UUID -> appender.append((UUID) value);
        }
    }

    private Optional<String> storedFingerprint(Connection connection) throws SQLException {
        try (Statement sql = connection.createStatement();
             ResultSet rs = sql.executeQuery(
                     "SELECT fingerprint FROM history_lake.projection_identity ORDER BY installed_at LIMIT 1")) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        }
    }

    private static String stagingName(String table) {
        return "proj_stage_" + table;
    }

    private static String encodeCounts(Map<String, Long> counts) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((table, count) -> {
            if (sb.length() > 0) sb.append(';');
            sb.append(table).append('=').append(count);
        });
        return sb.toString();
    }

    private static Map<String, Long> decodeCounts(String encoded) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return counts;
        for (String entry : encoded.split(";")) {
            int split = entry.lastIndexOf('=');
            if (split > 0) counts.put(entry.substring(0, split), Long.parseLong(entry.substring(split + 1)));
        }
        return counts;
    }

    private static boolean isCapacityFailure(SQLException e) {
        String message = e.getMessage();
        return message != null && (message.contains("Out of Memory") || message.contains("could not allocate")
                || message.contains("temporary directory"));
    }

    /**
     * One bounded maintenance pass.
     *
     * <p>Housekeeping runs <strong>first</strong> and against its own budget, then compaction
     * against a separate one. The order is deliberate: expiring snapshots and deleting
     * obsolete and orphaned files is how the archive reclaims disk, while compaction is only
     * an optimisation. Running compaction first — as the replay-worker path does — lets a
     * large rewrite consume the window and skip the cleanup entirely, which turns a lagging
     * archive into a full disk.
     *
     * <p>Compaction additionally runs only when the caller says it may (never during
     * bootstrap, where it would contend with the sink for the same bounded bulk pool) and
     * only when the file distribution says it would help.
     */
    @Override
    public ProjectionMaintenance.Result maintain(ProjectionMaintenance.Budget budget) {
        Objects.requireNonNull(budget, "budget");
        if (identity == null) {
            return ProjectionMaintenance.Result.deferred("sink is not initialized");
        }
        long start = System.nanoTime();
        long waitStart = System.nanoTime();

        try (DuckDbLease lease = manager.acquire(DuckDbWorkload.BULK_CATCH_UP, config.acquireTimeout())) {
            java.time.Duration writerWait = java.time.Duration.ofNanos(System.nanoTime() - waitStart);
            Connection connection = lease.connection();
            DuckLakeSql.attach(connection, config, null, false);
            try {
                java.util.OptionalLong filesBefore = countDataFiles(connection);

                // Keep the watermark optional. Substituting Long.MAX_VALUE would make the later
                // "files newer than the watermark" query return 0 rows and report 0 bytes
                // rewritten as though measured - the same fabricated-zero mistake this change
                // exists to remove, just one level further along.
                java.util.OptionalLong fileIdWatermark = maxDataFileId(connection);

                // --- mandatory housekeeping, budgeted on its own ---------------
                long housekeepingDeadline = System.nanoTime() + budget.housekeepingTimeLimit().toNanos();
                long snapshotsExpired = 0;
                long orphansDeleted = 0;
                java.util.List<String> housekeepingProblems = new java.util.ArrayList<>();
                boolean anyHousekeepingRan = false;
                if (budget.housekeepingTimeLimit().toNanos() > 0) {
                    var expire = runHousekeeping(connection, housekeepingDeadline, "expire_snapshots",
                            "CALL ducklake_expire_snapshots('history_lake', older_than => now() - INTERVAL '"
                                    + config.snapshotRetention().toSeconds() + " seconds')");
                    var cleanup = runHousekeeping(connection, housekeepingDeadline, "cleanup_old_files",
                            "CALL ducklake_cleanup_old_files('history_lake', older_than => now() - INTERVAL '"
                                    + config.cleanupGrace().toSeconds() + " seconds')");
                    var orphans = runHousekeeping(connection, housekeepingDeadline, "delete_orphaned_files",
                            "CALL ducklake_delete_orphaned_files('history_lake', older_than => now() - INTERVAL '"
                                    + config.cleanupGrace().toSeconds() + " seconds')");

                    snapshotsExpired = expire.rows();
                    orphansDeleted = orphans.rows();
                    for (var step : java.util.List.of(expire, cleanup, orphans)) {
                        if (step.ok()) anyHousekeepingRan = true;
                        else housekeepingProblems.add(step.diagnostic());
                    }

                    // Abandon the pass after ANY failed housekeeping CALL, rather than asking
                    // SELECT 1 whether the connection is still usable. A trivial query can
                    // succeed on a connection whose DuckLake transaction context is already
                    // ruined, so the probe answers a narrower question than the one that matters.
                    // Compaction on a pass whose mandatory housekeeping failed is not wanted
                    // anyway: cleanup is the part that reclaims space, and merging files while
                    // unable to expire snapshots only adds orphans.
                    if (!housekeepingProblems.isEmpty()) {
                        return finish(ProjectionMaintenance.Outcome.FAILED, start,
                                java.util.OptionalLong.empty(), java.util.OptionalLong.empty(),
                                java.util.OptionalLong.empty(), snapshotsExpired, orphansDeleted,
                                writerWait,
                                "housekeeping failed; pass abandoned and compaction skipped,"
                                        + " retrying on a fresh connection: "
                                        + String.join("; ", housekeepingProblems));
                    }
                }

                // A pass whose mandatory housekeeping did not fully succeed can never be
                // COMPLETED, whatever compaction did.
                if (!housekeepingProblems.isEmpty() && !anyHousekeepingRan) {
                    return finish(ProjectionMaintenance.Outcome.FAILED, start, filesBefore,
                            countDataFiles(connection), java.util.OptionalLong.empty(),
                            snapshotsExpired, orphansDeleted, writerWait,
                            "all housekeeping steps failed: " + String.join("; ", housekeepingProblems));
                }

                // --- optional, thresholded, byte-bounded compaction --------------
                boolean compacted = false;
                boolean compactionTruncated = false;
                java.util.List<String> compactionProblems = new java.util.ArrayList<>();
                if (budget.compactionAllowed() && budget.compactionTimeLimit().toNanos() > 0
                        && budget.maxBytesToRewrite() > 0) {
                    if (!compactionWorthwhile(connection, budget)) {
                        var files = countDataFiles(connection);
                        return finish(housekeepingProblems.isEmpty()
                                        ? ProjectionMaintenance.Outcome.UNNECESSARY
                                        : ProjectionMaintenance.Outcome.PARTIAL,
                                start, filesBefore, files,
                                java.util.OptionalLong.of(0), snapshotsExpired, orphansDeleted, writerWait,
                                housekeepingProblems.isEmpty()
                                        ? "file distribution is below the compaction threshold"
                                        : "housekeeping incomplete: " + String.join("; ", housekeepingProblems));
                    }
                    java.util.List<String> tables = compactableTables();

                    // The byte budget is enforced, not merely used as an enable flag. DuckLake
                    // applies max_compacted_files per table, so the aggregate allowance is
                    // divided by the table count; each call can then write at most
                    // maxFiles x targetFileSize bytes for its table.
                    int maxFiles = compactionOutputsPerTable(budget.maxBytesToRewrite(),
                            config.targetFileSizeBytes(), tables.size());

                    long compactionDeadline = System.nanoTime() + budget.compactionTimeLimit().toNanos();
                    for (int i = 0; i < tables.size(); i++) {
                        // Round-robin start, so one table that repeatedly fails to compact
                        // cannot starve every table behind it on every future pass.
                        String table = tables.get(Math.floorMod(nextCompactionTable + i, tables.size()));
                        if (System.nanoTime() >= compactionDeadline) {
                            compactionTruncated = true;
                            break;
                        }
                        try (Statement sql = connection.createStatement()) {
                            sql.execute("CALL ducklake_merge_adjacent_files('history_lake', '"
                                    + DuckLakeSql.name(table) + "', max_compacted_files => " + maxFiles + ")");
                            compacted = true;
                            nextCompactionTable = Math.floorMod(nextCompactionTable + i + 1, tables.size());
                        } catch (SQLException e) {
                            // A table that cannot compact within the reserved memory must not
                            // fail the whole pass, but it must be reported.
                            compactionTruncated = true;
                            compactionProblems.add(DuckLakeSql.name(table) + ": " + e.getMessage());
                            nextCompactionTable = Math.floorMod(nextCompactionTable + i + 1, tables.size());
                        }
                    }
                }

                var filesAfter = countDataFiles(connection);
                var bytesRewritten = fileIdWatermark.isPresent()
                        ? bytesWrittenSince(connection, fileIdWatermark.getAsLong())
                        : java.util.OptionalLong.empty();

                java.util.List<String> problems = new java.util.ArrayList<>(housekeepingProblems);
                problems.addAll(compactionProblems);
                ProjectionMaintenance.Outcome outcome =
                        (compactionTruncated || !problems.isEmpty())
                                ? ProjectionMaintenance.Outcome.PARTIAL
                                : ProjectionMaintenance.Outcome.COMPLETED;
                String detail;
                if (!problems.isEmpty()) {
                    detail = String.join("; ", problems);
                } else if (compactionTruncated) {
                    detail = "compaction stopped at its time budget";
                } else {
                    detail = compacted ? null : "housekeeping only";
                }
                return finish(outcome, start, filesBefore, filesAfter, bytesRewritten,
                        snapshotsExpired, orphansDeleted, writerWait, detail);
            } finally {
                DuckLakeSql.detach(connection);
            }
        } catch (SQLException e) {
            health = ProjectionSinkHealth.unavailable(e.toString());
            return new ProjectionMaintenance.Result(ProjectionMaintenance.Outcome.FAILED,
                    java.time.Duration.ofNanos(System.nanoTime() - start), java.util.OptionalLong.empty(),
                    java.util.OptionalLong.empty(), java.util.OptionalLong.empty(), 0, 0,
                    java.time.Duration.ZERO, Optional.of(e.toString()));
        }
    }

    private static ProjectionMaintenance.Result finish(ProjectionMaintenance.Outcome outcome, long start,
                                                       java.util.OptionalLong before,
                                                       java.util.OptionalLong after,
                                                       java.util.OptionalLong rewritten,
                                                       long snapshots, long orphans,
                                                       java.time.Duration writerWait, String detail) {
        return new ProjectionMaintenance.Result(outcome,
                java.time.Duration.ofNanos(System.nanoTime() - start), before, after, rewritten,
                snapshots, orphans, writerWait, Optional.ofNullable(detail));
    }

    /**
     * One housekeeping step's outcome.
     *
     * <p>Every step is <strong>mandatory</strong>. A step that fails or is cut off by the
     * deadline must be visible: silently returning zero lets a pass that reclaimed nothing
     * report {@code COMPLETED}, which is how an archive quietly stops cleaning up while its
     * status keeps saying it is healthy.
     *
     * @param rows      rows the call reported
     * @param state     what happened to this step
     * @param diagnostic failure text, or null when the step succeeded or was skipped
     */
    private record HousekeepingStep(long rows, StepState state, String diagnostic) {
        enum StepState { OK, FAILED, SKIPPED_DEADLINE }

        boolean ok() {
            return state == StepState.OK;
        }
    }

    private static HousekeepingStep runHousekeeping(Connection connection, long deadline,
                                                    String name, String command) {
        if (System.nanoTime() >= deadline) {
            return new HousekeepingStep(0, HousekeepingStep.StepState.SKIPPED_DEADLINE,
                    name + ": skipped, housekeeping budget exhausted");
        }
        try (Statement sql = connection.createStatement(); ResultSet rs = sql.executeQuery(command)) {
            long rows = 0;
            while (rs.next()) rows++;
            return new HousekeepingStep(rows, HousekeepingStep.StepState.OK, null);
        } catch (SQLException e) {
            // One step failing must not prevent the remaining steps from being attempted -
            // but it must be reported, and it must downgrade the pass outcome. Clear any
            // aborted transaction the failed CALL left behind, or every later query on this
            // connection - including the file counts this pass reports - fails too and the
            // diagnostics become useless.
            try {
                if (!connection.getAutoCommit()) connection.rollback();
            } catch (SQLException ignored) {
                // Nothing further to do; the outer diagnostic already carries the real failure.
            }
            return new HousekeepingStep(0, HousekeepingStep.StepState.FAILED,
                    name + ": " + e.getMessage());
        }
    }

    /** Total data files across the catalog, for threshold checks and reporting. */
    /**
     * Every table this sink writes, including its own bookkeeping.
     *
     * <p>{@code DuckLakeSql.tables()} enumerates the dataset tables only. The receipt table is
     * written on every commit and so accumulates one small file per commit — measured at
     * 10,515 three-KiB files, 84% of the active file count, on a preprod archive whose dataset
     * tables had already compacted to ~1,900. Leaving it out of compaction makes it the
     * dominant term in catalog size.
     */
    private static java.util.List<String> compactableTables() {
        var tables = new java.util.ArrayList<>(DuckLakeSql.tables().keySet());
        tables.add(DuckLakeProjectionSchema.RECEIPTS_TABLE);
        return tables;
    }

    /**
     * Round-robin cursor over compactable tables.
     *
     * <p>Bounded maintenance rarely reaches every table in one pass. Always starting at the
     * first table would mean the tail never compacts, and a table that repeatedly exhausts the
     * reserved memory would pin every table behind it forever.
     */
    private int nextCompactionTable;

    /**
     * Files the byte budget must cover, per table.
     *
     * <p>DuckLake applies {@code max_compacted_files} independently to each table, so the
     * aggregate allowance is divided by the table count rather than being multiplied by it.
     * Each call then writes at most {@code maxFiles x targetFileSize} bytes.
     *
     * <p><strong>Per-call upper bound.</strong> DuckLake cannot interrupt a
     * {@code merge_adjacent_files} call safely - it would roll the whole call back and lose the
     * work - so the time deadline is checked only between calls. The advertised byte budget is
     * therefore honoured as: at most one in-flight call may exceed the deadline, and that call
     * is itself bounded by {@code max_compacted_files}. Size the budget so that one table's
     * bounded call is an acceptable overshoot.
     */
    static int compactionOutputsPerTable(long maxBytesToRewrite, long targetFileSizeBytes, int tableCount) {
        if (maxBytesToRewrite < 1 || targetFileSizeBytes < 1 || tableCount < 1) {
            throw new IllegalArgumentException("invalid DuckLake compaction sizing inputs");
        }
        long aggregateOutputs = Math.max(1, maxBytesToRewrite / targetFileSizeBytes);
        return (int) Math.max(1, Math.min(100, aggregateOutputs / tableCount));
    }

    /**
     * Highest data-file id currently in the catalog; the watermark a pass measures against.
     *
     * <p>File ids are assigned monotonically, so a file this pass produced is exactly one whose
     * id is past the watermark. {@code begin_snapshot} cannot serve here: a merged file keeps
     * the earliest snapshot its rows came from, so it looks older than the pass that wrote it.
     */
    private static java.util.OptionalLong maxDataFileId(Connection connection) {
        try (Statement sql = connection.createStatement();
             ResultSet rs = sql.executeQuery(
                     "SELECT coalesce(max(data_file_id), 0) FROM"
                             + " __ducklake_metadata_history_lake.ducklake_data_file")) {
            return rs.next() ? java.util.OptionalLong.of(rs.getLong(1)) : java.util.OptionalLong.empty();
        } catch (SQLException e) {
            // Unknown, not zero. Reporting a failed measurement as 0 is how "files 35 -> 0" came
            // to read as a catastrophic deletion when nothing had been deleted at all.
            return java.util.OptionalLong.empty();
        }
    }

    /**
     * Bytes this pass wrote, measured from the catalog rather than estimated.
     *
     * <p>Output bytes, not input bytes, is the right pairing with the budget: DuckLake's
     * {@code max_compacted_files} bounds how many files a call may <em>produce</em>, so
     * {@code maxFiles x targetFileSize} bounds exactly this quantity. A compacted input is not
     * measurable after the fact - DuckLake removes it from {@code ducklake_data_file} and
     * records only its path in the deletion queue, with no size - whereas a file this pass
     * produced is exactly an active file whose id is past the watermark.
     *
     * <p>Returning a real number matters: an unenforced budget that reports {@code 0} looks
     * identical to a budget that was respected.
     */
    private static java.util.OptionalLong bytesWrittenSince(Connection connection, long fileIdWatermark) {
        try (Statement sql = connection.createStatement();
             ResultSet rs = sql.executeQuery(
                     "SELECT coalesce(sum(file_size_bytes), 0) FROM"
                             + " __ducklake_metadata_history_lake.ducklake_data_file"
                             + " WHERE end_snapshot IS NULL AND data_file_id > " + fileIdWatermark)) {
            return rs.next() ? java.util.OptionalLong.of(rs.getLong(1)) : java.util.OptionalLong.empty();
        } catch (SQLException e) {
            // Unknown, not zero. Reporting a failed measurement as 0 is how "files 35 -> 0" came
            // to read as a catastrophic deletion when nothing had been deleted at all.
            return java.util.OptionalLong.empty();
        }
    }

    private static java.util.OptionalLong countDataFiles(Connection connection) {
        try (Statement sql = connection.createStatement();
             ResultSet rs = sql.executeQuery(
                     "SELECT count(*) FROM ducklake_table_info('history_lake')")) {
            // table_info reports per-table file counts; sum them for a catalog total.
            return rs.next() ? sumFileCounts(connection) : java.util.OptionalLong.empty();
        } catch (SQLException e) {
            return java.util.OptionalLong.empty();
        }
    }

    private static java.util.OptionalLong sumFileCounts(Connection connection) {
        try (Statement sql = connection.createStatement();
             ResultSet rs = sql.executeQuery(
                     "SELECT coalesce(sum(file_count), 0) FROM ducklake_table_info('history_lake')")) {
            return rs.next() ? java.util.OptionalLong.of(rs.getLong(1)) : java.util.OptionalLong.empty();
        } catch (SQLException e) {
            // Unknown, not zero. Reporting a failed measurement as 0 is how "files 35 -> 0" came
            // to read as a catastrophic deletion when nothing had been deleted at all.
            return java.util.OptionalLong.empty();
        }
    }

    /**
     * Compact only when the distribution says it would help, rather than on every tick.
     * Blind periodic compaction rewrites already-large files for no benefit and consumes the
     * bulk pool the sink needs.
     */
    /**
     * Whether enough <em>small</em> files exist to be worth a compaction pass.
     *
     * <p>Counting all files instead would make this true forever on any large archive, so a
     * pass would be attempted on every interval — acquiring a bulk lease and scanning for
     * nothing. {@code merge_adjacent_files} is idempotent (a second pass over an already
     * compacted lake rewrites nothing, verified by test), so a loose gate is safe rather than
     * corrupting, but it is still wasted work.
     */
    private boolean compactionWorthwhile(Connection connection, ProjectionMaintenance.Budget budget) {
        long smallFileCeiling = Math.min(budget.minSmallFileBytes(), config.targetFileSizeBytes());
        var small = countSmallFiles(connection, smallFileCeiling);
        // Unknown distribution: attempt the pass. merge_adjacent_files is idempotent, so a
        // needless attempt is cheap, while skipping a needed one lets small files accumulate.
        return small.isEmpty() || small.getAsLong() >= budget.minFilesToCompact();
    }

    /**
     * Active files below {@code ceiling} bytes; these are the ones merging can help.
     *
     * <p>Read from DuckLake's own metadata schema. {@code ducklake_list_files} is per-table and
     * would need one call per table; the metadata schema answers it in one query, and it is the
     * same table the catalog stores file statistics in.
     */
    private static java.util.OptionalLong countSmallFiles(Connection connection, long ceiling) {
        try (Statement sql = connection.createStatement();
             ResultSet rs = sql.executeQuery(
                     "SELECT count(*) FROM __ducklake_metadata_history_lake.ducklake_data_file"
                             + " WHERE end_snapshot IS NULL AND file_size_bytes < " + ceiling)) {
            return rs.next() ? java.util.OptionalLong.of(rs.getLong(1)) : java.util.OptionalLong.empty();
        } catch (SQLException e) {
            return java.util.OptionalLong.empty();
        }
    }

    @Override
    public ProjectionSinkHealth health() {
        return health;
    }

    /**
     * Closes the sink and the {@link DuckDbManager} it owns.
     *
     * <p>The manager holds DuckDB connections and their native resources. Leaving it open
     * leaked a connection pool on every sink close, which repeated open/close cycles — a
     * restart loop, or a sink reopened after a failure — would accumulate.
     */
    @Override
    public void close() {
        health = ProjectionSinkHealth.unavailable("closed");
        try {
            manager.close();
        } catch (Exception e) {
            // Closing is best-effort; the sink is already unusable either way.
        }
    }
}
