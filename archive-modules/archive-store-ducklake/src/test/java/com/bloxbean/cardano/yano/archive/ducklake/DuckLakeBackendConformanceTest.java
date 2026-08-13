package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveMaintenanceBudget;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRetentionCutoff;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.SourceKind;
import com.bloxbean.cardano.yano.archive.api.test.AbstractArchiveBackendConformanceTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckLakeBackendConformanceTest extends AbstractArchiveBackendConformanceTest {
    @TempDir Path temp;

    @Override
    protected ArchiveBackend createBackend() {
        return open(identity(UUID.randomUUID()));
    }

    @Test
    void pinnedReaderKeepsOldRowsWhileWriterCommitsAndMaintenanceDefers() throws Exception {
        ArchiveBackend backend = backend();
        ArchiveJob first = job(0, 9, (byte) 1);
        commit(backend, first, row(first, (byte) 1, 9));
        try (var pinned = (DuckLakeReadSession) backend.openReadSession()) {
                assertThat(count(pinned, "chain_transaction")).isEqualTo(1);
                ArchiveJob second = job(10, 19, (byte) 2);
                commit(backend, second, row(second, (byte) 2, 19));
                assertThat(count(pinned, "chain_transaction")).isEqualTo(1);
                backend.maintain(new ArchiveMaintenanceBudget(Duration.ofSeconds(5), 1024 * 1024));
        }
        try (var current = (DuckLakeReadSession) backend.openReadSession()) {
            assertThat(count(current, "chain_transaction")).isEqualTo(2);
            assertThat(count(current, "transactions")).isEqualTo(2);
        }
    }

    @Test
    void retryWithDifferentRowsFailsAndInvalidationRemovesCoverage() {
        ArchiveBackend backend = backend();
        ArchiveJob job = job(0, 9, (byte) 1);
        commit(backend, job, row(job, (byte) 1, 9));
        assertThatThrownBy(() -> commit(backend, job, row(job, (byte) 9, 9)))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("different rows");

        backend.invalidate(ArchiveDatasetId.TRANSACTION, new BlockRange(0, 9));
        assertThat(backend.coverage(ArchiveDatasetId.TRANSACTION).covers(5)).isFalse();
        assertThat(backend.findReceipt(job.jobId())).isEmpty();
    }

    @Test
    void duplicateLogicalKeyAndWrongJobProvenanceFailBeforeCommit() {
        ArchiveBackend backend = backend();
        ArchiveJob job = job(0, 9, (byte) 1);
        assertThatThrownBy(() -> {
            try (var write = backend.begin(job)) {
                write.append(row(job, (byte) 1, 9));
                write.append(row(job, (byte) 1, 9));
                write.commit();
            }
        }).isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("duplicate logical primary key");

        ArchiveRow wrongProvenance = new ArchiveRow("chain_transaction", List.of(
                new byte[32], new byte[32], 9L, 90L, 0L, 0L, 0, true, 10L, UUID.randomUUID()));
        assertThatThrownBy(() -> {
            try (var write = backend.begin(job)) {
                write.append(wrongProvenance);
            }
        }).isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("archive_job_id");
    }

    @Test
    void sessionMayBeAbortedByAnotherExecutorWithoutLeakingWriterPermit() throws Exception {
        ArchiveJob job = job(0, 9, (byte) 7);
        var abandoned = backend().begin(job);
        abandoned.append(row(job, (byte) 7, 9));
        Thread closer = Thread.ofVirtual().start(abandoned::close);
        closer.join();

        commit(backend(), job, row(job, (byte) 7, 9));
        assertThat(backend().findReceipt(job.jobId())).isPresent();
    }

    @Test
    void identityMismatchAndSecondWriterFailClosed() {
        ArchiveIdentity firstIdentity = backend().identity();
        backend().close();
        try (var first = open(firstIdentity)) {
            assertThatThrownBy(() -> open(firstIdentity))
                    .isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("already has a writer");
        }
        assertThatThrownBy(() -> open(new ArchiveIdentity(UUID.randomUUID(), "ducklake", 1, 2, "other-genesis")))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("identity mismatch");
    }

    @Test
    void committedRowsAreParquetBackedWithInliningDisabled() throws Exception {
        ArchiveJob job = job(0, 0, (byte) 1);
        commit(backend(), job, row(job, (byte) 1, 0));
        var duckLake = (DuckLakeHistoryArchiveBackend) backend();
        duckLake.verifyIntegrity(Duration.ofSeconds(5));
        Path backup = duckLake.backupCatalog(temp.resolve("backups/catalog.sqlite"));
        assertThat(backup).isRegularFile();
        assertThat(Files.size(backup)).isPositive();
        backend().close();
        try (var paths = Files.walk(temp.resolve("data"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".parquet")).count())
                    .isPositive();
        }
    }

    @Test
    void restartDiscoversCommittedReceiptAndMakesRetryIdempotent() {
        ArchiveIdentity identity = backend().identity();
        ArchiveJob job = job(0, 9, (byte) 3);
        ArchiveRow row = row(job, (byte) 3, 9);
        commit(backend(), job, row);
        var expected = backend().findReceipt(job.jobId()).orElseThrow();
        backend().close();

        try (var reopened = open(identity)) {
            assertThat(reopened.findReceipt(job.jobId())).contains(expected);
            commit(reopened, job, row);
            assertThat(reopened.findReceipt(job.jobId())).contains(expected);
            assertThat(reopened.coverage(ArchiveDatasetId.TRANSACTION).completeRanges())
                    .containsExactly(new BlockRange(0, 9));
        }
    }

    @Test
    void transactionLocatorSurvivesRestartAndVerifiesAgainstPinnedRows() {
        ArchiveIdentity identity = backend().identity();
        ArchiveJob job = job(20, 29, (byte) 8);
        byte[] txHash = new byte[32];
        Arrays.fill(txHash, (byte) 8);
        commit(backend(), job, row(job, (byte) 8, 23));
        try (var read = backend().openReadSession()) {
            assertThat(backend().findTransaction(read, txHash)).isPresent();
        }
        backend().close();

        try (var reopened = open(identity); var read = reopened.openReadSession()) {
            assertThat(reopened.findTransaction(read, txHash)).isPresent();
            reopened.invalidate(ArchiveDatasetId.TRANSACTION, new BlockRange(20, 29));
        }
        try (var reopened = open(identity); var read = reopened.openReadSession()) {
            assertThat(reopened.findTransaction(read, txHash)).isEmpty();
        }
    }

    @Test
    void transactionLocatorHonorsEachPinnedGenerationAcrossInvalidation() {
        ArchiveJob job = job(30, 39, (byte) 9);
        byte[] txHash = new byte[32];
        Arrays.fill(txHash, (byte) 9);
        commit(backend(), job, row(job, (byte) 9, 33));
        try (var before = backend().openReadSession()) {
            backend().invalidate(ArchiveDatasetId.TRANSACTION, new BlockRange(30, 39));
            try (var current = backend().openReadSession()) {
                assertThat(backend().findTransaction(current, txHash)).isEmpty();
            }
            assertThat(backend().findTransaction(before, txHash)).isPresent();
        }
    }

    @Test
    void independentReadOnlyDuckDbClientCanQueryCommittedArchive() throws Exception {
        ArchiveJob job = job(0, 9, (byte) 6);
        commit(backend(), job, row(job, (byte) 6, 9));
        DuckLakeArchiveConfig archive = config();
        try (var independent = new DuckDbManager(DuckDbManagerConfig.defaults(temp.resolve("reader-tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("reader-extensions")));
             var lease = independent.acquire(DuckDbWorkload.STEADY, Duration.ofSeconds(5))) {
            DuckLakeSql.attach(lease.connection(), archive, null, true);
            try (var query = lease.connection().createStatement();
                 var result = query.executeQuery("SELECT count(*) FROM history_lake.transactions")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isEqualTo(1);
            } finally {
                DuckLakeSql.detach(lease.connection());
            }
        }
    }

    @Test
    void retentionRemovesOnlyWholeCommittedRangesInANewSnapshot() throws Exception {
        ArchiveJob first = job(0, 9, (byte) 4);
        ArchiveJob second = job(10, 19, (byte) 5);
        commit(backend(), first, row(first, (byte) 4, 9));
        commit(backend(), second, row(second, (byte) 5, 19));
        try (var before = (DuckLakeReadSession) backend().openReadSession()) {
            backend().applyRetention(ArchiveDatasetId.TRANSACTION,
                    new ArchiveRetentionCutoff(SourceKind.BLOCK, 10));
            assertThat(count(before, "chain_transaction")).isEqualTo(2);
        }
        assertThat(backend().coverage(ArchiveDatasetId.TRANSACTION).completeRanges())
                .containsExactly(new BlockRange(10, 19));
        try (var after = (DuckLakeReadSession) backend().openReadSession()) {
            assertThat(count(after, "chain_transaction")).isEqualTo(1);
        }
    }

    private DuckLakeHistoryArchiveBackend open(ArchiveIdentity identity) {
        return DuckLakeHistoryArchiveBackend.open(identity, config(),
                DuckDbManagerConfig.defaults(temp.resolve("tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")));
    }

    private DuckLakeArchiveConfig config() {
        return new DuckLakeArchiveConfig(temp.resolve("catalog.sqlite"),
                temp.resolve("data"), Duration.ofSeconds(5), 10, 10,
                16L * 1024 * 1024, 10_000, Duration.ofHours(168), Duration.ofHours(24));
    }

    private ArchiveIdentity identity(UUID id) {
        return new ArchiveIdentity(id, "ducklake", 1, 1, "fixture-genesis");
    }

    private ArchiveJob job(long from, long to, byte marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, marker);
        return ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture-genesis"),
                ArchiveDatasetId.TRANSACTION, 1, new BlockRange(from, to),
                new ArchiveRangeAnchor(from * 10, hash, to * 10, hash), "fixture-v1");
    }

    private ArchiveRow row(ArchiveJob job, byte marker, long block) {
        byte[] txHash = new byte[32];
        byte[] blockHash = new byte[32];
        Arrays.fill(txHash, marker);
        Arrays.fill(blockHash, marker);
        return new ArchiveRow("chain_transaction", List.of(txHash, blockHash, block, block * 10,
                0L, 0L, 0, true, 10L, job.jobId()));
    }

    private void commit(ArchiveBackend backend, ArchiveJob job, ArchiveRow row) {
        try (var write = backend.begin(job)) {
            write.append(row);
            write.commit();
        }
    }

    private long count(DuckLakeReadSession session, String table) throws Exception {
        try (var sql = session.connection().createStatement();
             var result = sql.executeQuery("SELECT count(*) FROM history_lake." + DuckLakeSql.name(table))) {
            result.next();
            return result.getLong(1);
        }
    }
}
