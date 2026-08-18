package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveCoverage;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveColumn;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-038 Phase 2: proves the Appender staging path (shape (b)) is observationally
 * identical to the legacy prepared-statement path.
 *
 * <p>Equivalence is asserted on the artefacts the archive contract depends on:
 * the ordered digest, per-table row counts, the receipt, coverage, and the rows
 * themselves. Both paths are exercised through the same public session API, with
 * the temporary {@link DuckLakeWriteSession#APPEND_MODE_PROPERTY} rollback switch
 * selecting the implementation.
 */
class DuckLakeAppenderEquivalenceTest {
    @TempDir Path temp;

    @AfterEach
    void clearMode() {
        System.clearProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY);
    }

    private DuckLakeHistoryArchiveBackend open(String name) throws Exception {
        Path root = temp.resolve(name);
        Files.createDirectories(root);
        return DuckLakeHistoryArchiveBackend.open(
                new ArchiveIdentity(UUID.randomUUID(), "ducklake", 1, 1, "fixture-genesis"),
                new DuckLakeArchiveConfig(root.resolve("catalog.sqlite"), root.resolve("data"),
                        Duration.ofSeconds(30), 10, 10, 16L * 1024 * 1024, 100_000,
                        Duration.ofHours(168), Duration.ofHours(24)),
                DuckDbManagerConfig.defaults(root.resolve("tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
    }

    private static ArchiveJob utxoJob(long from, long to, byte marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.UTXO_HISTORY,
                ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).projectionVersion(),
                new BlockRange(from, to), new ArchiveRangeAnchor(from * 10, hash, to * 10, hash),
                "canonical-block-v1");
    }

    private static Object value(ArchiveColumn column, long unique, UUID job) {
        return switch (column.type()) {
            case BINARY -> {
                byte[] bytes = new byte[32];
                bytes[0] = (byte) unique; bytes[1] = (byte) (unique >>> 8);
                bytes[2] = (byte) column.name().hashCode();
                yield bytes;
            }
            case TEXT -> "t" + unique + '_' + column.name();
            case BOOLEAN -> (unique & 1) == 0;
            case INT32 -> (int) (unique % 977);
            case INT64 -> unique;
            case DECIMAL_38 -> BigDecimal.valueOf(unique + 3L);
            case UUID -> job;
        };
    }

    private static ArchiveRow row(ArchiveTableSchema table, long unique, UUID job, boolean nullable) {
        List<Object> values = new ArrayList<>(table.columns().size());
        for (ArchiveColumn column : table.columns()) {
            if (column.name().equals("archive_job_id")) values.add(job);
            // Exercise the appendNull() path on optional columns.
            else if (nullable && column.nullable() && !table.primaryKey().contains(column.name())
                    && (unique % 3 == 0)) values.add(null);
            else values.add(value(column, unique, job));
        }
        return new ArchiveRow(table.physicalName(), values);
    }

    /** Interleaves all five UTXO tables so several Appenders stay open concurrently. */
    private static List<ArchiveRow> interleavedUtxoRows(UUID job, int perTable) {
        List<ArchiveTableSchema> tables = ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).tables();
        List<ArchiveRow> rows = new ArrayList<>();
        long unique = 0;
        for (int i = 0; i < perTable; i++) {
            for (ArchiveTableSchema table : tables) {
                rows.add(row(table, unique++, job, true));
            }
        }
        return rows;
    }

    private record Outcome(String digest, Map<String, Long> counts, long generation,
                           Map<String, Long> tableRows, ArchiveCoverage coverage) { }

    private Outcome runOnce(String mode, String dir) throws Exception {
        System.setProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY, mode);
        try (var backend = open(dir)) {
            ArchiveJob job = utxoJob(1, 100, (byte) 7);
            List<ArchiveRow> rows = interleavedUtxoRows(job.jobId(), 40);
            ArchiveReceipt receipt;
            try (var write = backend.begin(job)) {
                for (ArchiveRow row : rows) write.append(row);
                receipt = write.commit();
            }
            Map<String, Long> tableRows = new LinkedHashMap<>();
            try (var read = (DuckLakeReadSession) backend.openReadSession()) {
                for (ArchiveTableSchema table : ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).tables()) {
                    try (var sql = read.connection().createStatement();
                         var result = sql.executeQuery("SELECT count(*) FROM history_lake."
                                 + DuckLakeSql.name(table.physicalName()))) {
                        result.next();
                        tableRows.put(table.physicalName(), result.getLong(1));
                    }
                }
            }
            return new Outcome(receipt.orderedDigest(), receipt.rowCounts(), receipt.backendGeneration(),
                    tableRows, backend.coverage(ArchiveDatasetId.UTXO_HISTORY));
        }
    }

    @Test
    void appenderProducesByteIdenticalDigestCountsAndCoverageAsLegacy() throws Exception {
        Outcome legacy = runOnce("legacy", "legacy");
        Outcome appender = runOnce("appender", "appender");

        assertThat(appender.digest())
                .as("ordered digest must be byte-identical across append implementations")
                .isEqualTo(legacy.digest());
        assertThat(appender.counts()).isEqualTo(legacy.counts());
        assertThat(appender.generation()).isEqualTo(legacy.generation());
        assertThat(appender.tableRows()).isEqualTo(legacy.tableRows());
        assertThat(appender.coverage().completeRanges()).isEqualTo(legacy.coverage().completeRanges());
        assertThat(appender.tableRows().values()).allMatch(count -> count == 40L);
    }

    @Test
    void appenderPathAcceptsReplayAndRejectsDifferentRows() throws Exception {
        System.setProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY, "appender");
        try (var backend = open("replay")) {
            ArchiveJob job = utxoJob(1, 100, (byte) 7);
            List<ArchiveRow> rows = interleavedUtxoRows(job.jobId(), 10);
            ArchiveReceipt first;
            try (var write = backend.begin(job)) {
                for (ArchiveRow row : rows) write.append(row);
                first = write.commit();
            }
            ArchiveReceipt replayed;
            try (var write = backend.begin(job)) {
                for (ArchiveRow row : rows) write.append(row);
                replayed = write.commit();
            }
            assertThat(replayed.orderedDigest()).isEqualTo(first.orderedDigest());
            assertThat(replayed.rowCounts()).isEqualTo(first.rowCounts());

            assertThatThrownBy(() -> {
                try (var write = backend.begin(job)) {
                    for (int i = 0; i < rows.size() - 1; i++) write.append(rows.get(i));
                    write.commit();
                }
            }).isInstanceOf(ArchiveStoreException.class).hasMessageContaining("different rows");
        }
    }

    @Test
    void failureDuringAppendRollsBackAndReleasesTheWriter() throws Exception {
        System.setProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY, "appender");
        try (var backend = open("append-failure")) {
            ArchiveJob job = utxoJob(1, 100, (byte) 7);
            List<ArchiveRow> rows = interleavedUtxoRows(job.jobId(), 20);

            assertThatThrownBy(() -> {
                try (var write = backend.begin(job)) {
                    for (ArchiveRow row : rows) write.append(row);
                    // Not part of UTXO_HISTORY: fails after several Appenders are open.
                    write.append(new ArchiveRow("chain_transaction", List.of(new byte[32], new byte[32],
                            1L, 1L, 0L, 0L, 0, true, 1L, job.jobId())));
                    write.commit();
                }
            }).isInstanceOf(ArchiveStoreException.class);

            try (var read = (DuckLakeReadSession) backend.openReadSession();
                 var sql = read.connection().createStatement();
                 var result = sql.executeQuery("SELECT count(*) FROM history_lake.transaction_outputs")) {
                result.next();
                assertThat(result.getLong(1)).as("failed session publishes nothing").isZero();
            }

            // Writer permit and Appenders released: a fresh session must succeed.
            ArchiveJob next = utxoJob(101, 200, (byte) 8);
            try (var write = backend.begin(next)) {
                for (ArchiveRow row : interleavedUtxoRows(next.jobId(), 5)) write.append(row);
                write.commit();
            }
            assertThat(backend.coverage(ArchiveDatasetId.UTXO_HISTORY).completeRanges()).hasSize(1);
        }
    }

    @Test
    void failureAfterAppendersCloseRollsBackEverything() throws Exception {
        System.setProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY, "appender");
        try (var backend = open("post-append-failure")) {
            ArchiveJob job = utxoJob(1, 100, (byte) 7);
            List<ArchiveRow> rows = new ArrayList<>(interleavedUtxoRows(job.jobId(), 5));
            // Duplicate logical key: fails in verifyLogicalKeys(), immediately after
            // every Appender has been flushed and closed.
            rows.add(rows.getFirst());

            assertThatThrownBy(() -> {
                try (var write = backend.begin(job)) {
                    for (ArchiveRow row : rows) write.append(row);
                    write.commit();
                }
            }).isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("duplicate logical primary key");

            try (var read = (DuckLakeReadSession) backend.openReadSession();
                 var sql = read.connection().createStatement();
                 var result = sql.executeQuery("SELECT count(*) FROM history_lake.transaction_outputs")) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }

    @Test
    void explicitCloseWithoutCommitPublishesNothingOnAppenderPath() throws Exception {
        System.setProperty(DuckLakeWriteSession.APPEND_MODE_PROPERTY, "appender");
        try (var backend = open("abort")) {
            ArchiveJob job = utxoJob(1, 100, (byte) 7);
            var write = backend.begin(job);
            for (ArchiveRow row : interleavedUtxoRows(job.jobId(), 10)) write.append(row);
            write.close();
            write.close(); // idempotent

            try (var read = (DuckLakeReadSession) backend.openReadSession();
                 var sql = read.connection().createStatement();
                 var result = sql.executeQuery("SELECT count(*) FROM history_lake.transaction_outputs")) {
                result.next();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }
}
