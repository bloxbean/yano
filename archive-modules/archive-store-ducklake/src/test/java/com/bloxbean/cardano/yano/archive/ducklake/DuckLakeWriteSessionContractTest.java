package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveReceipt;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-038 Phase 0: characterises the full production write contract, not merely
 * successful insertion, so that Phase 2's Appender path can be proven equivalent
 * rather than assumed so.
 *
 * <p>Each test states a contract Phase 2 must preserve: explicit transaction
 * commit and rollback, mid-append failure containment, digest and replay
 * equivalence, logical-key verification within a batch and against the target,
 * and a {@code UTXO_HISTORY} session spanning all five tables.
 */
class DuckLakeWriteSessionContractTest {
    @TempDir Path temp;

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

    private static ArchiveJob txJob(long from, long to, byte marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.TRANSACTION,
                ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).projectionVersion(),
                new BlockRange(from, to), new ArchiveRangeAnchor(from * 10, hash, to * 10, hash),
                "canonical-block-v1");
    }

    private static ArchiveRow txRow(ArchiveJob job, int unique, long block) {
        byte[] txHash = new byte[32];
        txHash[0] = (byte) unique; txHash[1] = (byte) (unique >>> 8);
        byte[] blockHash = new byte[32];
        blockHash[0] = (byte) block;
        return new ArchiveRow("chain_transaction", List.of(txHash, blockHash, block, block * 10,
                0L, 0L, unique % 26, true, 10L, job.jobId()));
    }

    private static long count(DuckLakeHistoryArchiveBackend backend, String table) throws Exception {
        try (var read = (DuckLakeReadSession) backend.openReadSession();
             var sql = read.connection().createStatement();
             var result = sql.executeQuery("SELECT count(*) FROM history_lake." + DuckLakeSql.name(table))) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    void commitPublishesRowsAndAbortRollsBackTheTransaction() throws Exception {
        try (var backend = open("commit-abort")) {
            ArchiveJob committed = txJob(1, 10, (byte) 1);
            try (var write = backend.begin(committed)) {
                write.append(txRow(committed, 1, 1));
                write.append(txRow(committed, 2, 2));
                write.commit();
            }
            assertThat(count(backend, "chain_transaction")).isEqualTo(2);

            // Closing without commit must roll the explicit transaction back.
            ArchiveJob abandoned = txJob(11, 20, (byte) 2);
            try (var write = backend.begin(abandoned)) {
                write.append(txRow(abandoned, 3, 11));
                write.append(txRow(abandoned, 4, 12));
            }
            assertThat(count(backend, "chain_transaction"))
                    .as("abandoned session must publish nothing").isEqualTo(2);
        }
    }

    @Test
    void midAppendFailureLeavesNoPartialRowsAndReleasesTheSession() throws Exception {
        try (var backend = open("mid-append")) {
            ArchiveJob seeded = txJob(1, 10, (byte) 1);
            try (var write = backend.begin(seeded)) {
                write.append(txRow(seeded, 1, 1));
                write.commit();
            }

            ArchiveJob failing = txJob(11, 20, (byte) 2);
            assertThatThrownBy(() -> {
                try (var write = backend.begin(failing)) {
                    for (int i = 0; i < 900; i++) write.append(txRow(failing, 100 + i, 11));
                    // A row whose table is not part of the dataset fails mid-append,
                    // after several staging flushes have already run.
                    write.append(new ArchiveRow("epoch_stakes", List.of(1L, "k", new byte[]{1},
                            BigDecimal.ONE, BigDecimal.ONE, failing.jobId())));
                    write.commit();
                }
            }).isInstanceOf(ArchiveStoreException.class);

            assertThat(count(backend, "chain_transaction"))
                    .as("failed session must not publish staged rows").isEqualTo(1);

            // The writer permit must be free for the next session.
            ArchiveJob next = txJob(21, 30, (byte) 3);
            try (var write = backend.begin(next)) {
                write.append(txRow(next, 500, 21));
                write.commit();
            }
            assertThat(count(backend, "chain_transaction")).isEqualTo(2);
        }
    }

    @Test
    void replayOfCommittedJobReturnsIdenticalDigestAndRejectsDifferentRows() throws Exception {
        try (var backend = open("replay")) {
            ArchiveJob job = txJob(1, 10, (byte) 1);
            ArchiveReceipt first;
            try (var write = backend.begin(job)) {
                write.append(txRow(job, 1, 1));
                write.append(txRow(job, 2, 2));
                first = write.commit();
            }

            ArchiveReceipt replayed;
            try (var write = backend.begin(job)) {
                write.append(txRow(job, 1, 1));
                write.append(txRow(job, 2, 2));
                replayed = write.commit();
            }
            assertThat(replayed.orderedDigest()).isEqualTo(first.orderedDigest());
            assertThat(replayed.rowCounts()).isEqualTo(first.rowCounts());
            assertThat(replayed.backendGeneration()).isEqualTo(first.backendGeneration());
            assertThat(count(backend, "chain_transaction"))
                    .as("idempotent replay must not double-write").isEqualTo(2);

            assertThatThrownBy(() -> {
                try (var write = backend.begin(job)) {
                    write.append(txRow(job, 1, 1));
                    write.append(txRow(job, 99, 2));
                    write.commit();
                }
            }).isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("different rows");
        }
    }

    @Test
    void logicalKeyVerificationRejectsDuplicatesWithinBatchAndAgainstTarget() throws Exception {
        try (var backend = open("logical-keys")) {
            ArchiveJob duplicateInBatch = txJob(1, 10, (byte) 1);
            assertThatThrownBy(() -> {
                try (var write = backend.begin(duplicateInBatch)) {
                    write.append(txRow(duplicateInBatch, 1, 1));
                    write.append(txRow(duplicateInBatch, 1, 1));
                    write.commit();
                }
            }).isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("duplicate logical primary key");
            assertThat(count(backend, "chain_transaction")).isZero();

            ArchiveJob seeded = txJob(1, 10, (byte) 2);
            try (var write = backend.begin(seeded)) {
                write.append(txRow(seeded, 1, 1));
                write.commit();
            }

            // Same logical key, different job, overlapping block range.
            ArchiveJob collides = txJob(1, 10, (byte) 3);
            assertThatThrownBy(() -> {
                try (var write = backend.begin(collides)) {
                    write.append(txRow(collides, 1, 1));
                    write.commit();
                }
            }).isInstanceOf(ArchiveStoreException.class);
            assertThat(count(backend, "chain_transaction")).isEqualTo(1);
        }
    }

    @Test
    void utxoHistorySessionCommitsAcrossAllFiveTables() throws Exception {
        try (var backend = open("utxo-five")) {
            var schema = ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY);
            byte[] hash = new byte[32];
            Arrays.fill(hash, (byte) 5);
            ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                    ArchiveDatasetId.UTXO_HISTORY, schema.projectionVersion(),
                    new BlockRange(1, 10), new ArchiveRangeAnchor(10, hash, 100, hash),
                    "canonical-block-v1");

            List<String> tables = new ArrayList<>();
            try (var write = backend.begin(job)) {
                int unique = 0;
                for (ArchiveTableSchema table : schema.tables()) {
                    tables.add(table.physicalName());
                    for (int i = 0; i < 3; i++) {
                        write.append(utxoRow(table, unique++, job.jobId()));
                    }
                }
                ArchiveReceipt receipt = write.commit();
                assertThat(receipt.rowCounts()).hasSize(tables.size());
                assertThat(receipt.rowCounts().values()).allMatch(value -> value == 3L);
            }

            assertThat(tables).hasSize(5);
            for (String table : tables) {
                assertThat(count(backend, table)).as(table).isEqualTo(3);
            }
        }
    }

    private static ArchiveRow utxoRow(ArchiveTableSchema table, int unique, UUID job) {
        List<Object> values = new ArrayList<>(table.columns().size());
        for (var column : table.columns()) {
            values.add(switch (column.type()) {
                case BINARY -> {
                    byte[] bytes = new byte[32];
                    bytes[0] = (byte) unique;
                    bytes[1] = (byte) column.name().hashCode();
                    yield bytes;
                }
                case TEXT -> "v" + unique;
                case BOOLEAN -> true;
                case INT32 -> unique;
                case INT64 -> (long) unique;
                case DECIMAL_38 -> BigDecimal.valueOf(unique + 1L);
                case UUID -> job;
            });
        }
        return new ArchiveRow(table.physicalName(), values);
    }
}
