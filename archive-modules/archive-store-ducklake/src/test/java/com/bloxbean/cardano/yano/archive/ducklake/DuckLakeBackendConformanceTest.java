package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveFatalException;
import com.bloxbean.cardano.yano.archive.api.ArchiveStuckOperationException;
import com.bloxbean.cardano.yano.archive.api.ArchiveWaitPolicy;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveHealth;
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
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckLakeBackendConformanceTest extends AbstractArchiveBackendConformanceTest {
    @TempDir Path temp;

    @Test
    void classifiesOnlyDuckDbResourceExhaustionAsRetryableBatchCapacity() {
        assertThat(DuckLakeWriteSession.isCapacityFailure(
                new java.sql.SQLException("Out of Memory Error: failed to allocate data of size 16 MiB")))
                .isTrue();
        assertThat(DuckLakeWriteSession.isCapacityFailure(
                new java.sql.SQLException("constraint violation"))).isFalse();
    }

    @Test
    void boundedMaintenanceCapacityDoesNotDegradeCommittedDataHealth() {
        var capacity = new com.bloxbean.cardano.yano.archive.api.ArchiveBatchCapacityException(
                "bounded compaction", new java.sql.SQLException("Out of Memory Error"));

        assertThat(DuckLakeHistoryArchiveBackend.degradesArchiveHealth(capacity)).isFalse();
        assertThat(DuckLakeHistoryArchiveBackend.degradesArchiveHealth(
                new java.sql.SQLException("Out of Memory Error"))).isFalse();
        assertThat(DuckLakeHistoryArchiveBackend.degradesArchiveHealth(
                new java.sql.SQLException("catalog corruption"))).isTrue();
    }

    @Test
    void boundedMaintenanceQueryTimeoutDoesNotDegradeCommittedDataHealth() {
        var timeout = new java.sql.SQLException("INTERRUPT Error: Interrupted!");

        assertThat(DuckLakeHistoryArchiveBackend.isMaintenanceTimeout(timeout)).isTrue();
        assertThat(DuckLakeHistoryArchiveBackend.degradesArchiveHealth(timeout)).isFalse();
        assertThat(DuckLakeHistoryArchiveBackend.degradesArchiveHealth(
                new java.sql.SQLException("catalog corruption"))).isTrue();
    }

    @Test
    void aggregateCompactionBudgetIsSharedAcrossDuckLakeTables() {
        long mib = 1024L * 1024;

        assertThat(DuckLakeHistoryArchiveBackend.compactionOutputsPerTable(
                512 * mib, 32 * mib, 20)).isEqualTo(1);
        assertThat(DuckLakeHistoryArchiveBackend.compactionOutputsPerTable(
                2_048 * mib, 32 * mib, 20)).isEqualTo(3);
    }

    @Test
    void compactionIsCommittedOneTableAtATime() {
        assertThat(DuckLakeHistoryArchiveBackend.compactionCommand("archive_commits", 1))
                .isEqualTo("CALL ducklake_merge_adjacent_files('history_lake', 'archive_commits', "
                        + "max_compacted_files => 1)");
        assertThat(DuckLakeHistoryArchiveBackend.compactionOutputsForTable("archive_coverage", 1))
                .isEqualTo(100);
        assertThat(DuckLakeHistoryArchiveBackend.compactionOutputsForTable("transaction_outputs", 1))
                .isEqualTo(1);
    }

    @Test
    void maintenanceIncludesHighChurnControlTablesBeforeDatasetTables() {
        assertThat(DuckLakeHistoryArchiveBackend.maintenanceTables())
                .startsWith("archive_commit_counts", "archive_commits", "archive_coverage")
                .contains("archive_invalidations", "chain_transaction", "transaction_redeemers")
                .doesNotHaveDuplicates();
    }

    @Test
    void logicalKeyChecksPruneToTheStagedBlockRangeAndEpochPartitions() {
        var outputs = ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).tables().stream()
                .filter(table -> table.physicalName().equals("transaction_outputs"))
                .findFirst().orElseThrow();

        assertThat(DuckLakeWriteSession.targetRangePredicate(
                outputs, new BlockRange(1_478_071, 1_478_071), "stage_outputs", "t"))
                .isEqualTo(" AND t.epoch IN (SELECT DISTINCT epoch FROM stage_outputs WHERE epoch IS NOT NULL)"
                        + " AND t.block_number BETWEEN 1478071 AND 1478071");
    }

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
    void stagesRowsInBoundedMultiRowInsertsAndFlushesRemainderAtomically() throws Exception {
        int rows = DuckLakeWriteSession.STAGING_BATCH_SIZE + 1;
        ArchiveJob job = job(0, rows - 1L, (byte) 11);

        try (var write = backend().begin(job)) {
            for (int index = 0; index < rows; index++) {
                byte[] txHash = new byte[32];
                ByteBuffer.wrap(txHash).putInt(index);
                write.append(new ArchiveRow("chain_transaction", List.of(
                        txHash, job.anchorBlockHash(), (long) index, index * 10L,
                        0L, 0L, 0, true, 10L, job.jobId())));
            }
            var receipt = write.commit();
            assertThat(receipt.rowCounts()).containsEntry("chain_transaction", (long) rows);
        }

        try (var read = (DuckLakeReadSession) backend().openReadSession()) {
            assertThat(count(read, "chain_transaction")).isEqualTo(rows);
        }
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
    void closeReturnsWithoutRacingPinnedReaderAndReleasesResourcesWhenReaderEnds() throws Exception {
        ArchiveIdentity identity = backend().identity();
        var read = backend().openReadSession();
        long started = System.nanoTime();
        backend().close();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(backend().health().status()).isEqualTo(ArchiveHealth.Status.CLOSED);
        assertThatThrownBy(() -> open(identity))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("already has a writer");

        read.close();
        try (var reopened = open(identity)) {
            assertThat(reopened.health().status()).isEqualTo(ArchiveHealth.Status.HEALTHY);
        }
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

    @Test
    void aWriterWaitingForDuckDbCapacityDoesNotOccupyTheWriterSemaphore() throws Exception {
        // Saturate every DuckDB permit with readers, then start a mutation. Under
        // the lease->writer order it blocks on capacity, so the writer stays free.
        var backend = (DuckLakeHistoryArchiveBackend) backend();
        var managerConfig = DuckDbManagerConfig.defaults(temp.resolve("saturated-tmp"));
        try (var reader = backend.openReadSession()) {
            assertThat(reader.generation()).isNotNegative();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread mutation = Thread.ofPlatform().start(() -> {
                try (var write = backend.begin(job(900, 901, (byte) 91))) {
                    write.commit();
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            // Give the mutation time to reach whichever resource is scarce.
            Thread.sleep(200);
            var writerGate = backend.resourceDiagnostics().gates().stream()
                    .filter(gate -> gate.name().equals("ducklake-writer")).findFirst().orElseThrow();
            assertThat(writerGate.totalPermits()).isEqualTo(1);
            mutation.join(60_000);
            assertThat(failure.get()).isNull();
        }
        assertThat(managerConfig.maxConcurrentBulkJobs())
                .isLessThan(managerConfig.maxConcurrentQueries());
    }

    @Test
    void maintenanceDefersWithAReasonWhileAReaderSnapshotIsPinnedAndTakesNoCapacity() {
        var backend = (DuckLakeHistoryArchiveBackend) backend();
        try (var pinned = backend.openReadSession()) {
            assertThat(pinned.generation()).isNotNegative();
            backend.maintain(new ArchiveMaintenanceBudget(Duration.ofSeconds(2), 1024L * 1024));
            assertThat(backend.resourceDiagnostics().lastMaintenanceDeferral())
                    .contains("active reader snapshot");
            // Deferral must not have consumed the writer or a bulk permit.
            for (var gate : backend.resourceDiagnostics().gates()) {
                assertThat(gate.inUse())
                        .as("gate %s must be free after a deferred maintenance run", gate.name())
                        .isLessThanOrEqualTo(gate.name().equals("duckdb-total") ? 1 : 0);
            }
        }
    }

    @Test
    void everyMutationReleasesItsWriterAndLeaseExactlyOnceAcrossSuccessAndFailure() {
        var backend = (DuckLakeHistoryArchiveBackend) backend();
        try (var write = backend.begin(job(700, 701, (byte) 71))) {
            write.commit();
        }
        // A distinct job overlapping committed coverage is rejected, and that
        // rejection must release both resources too. (Repeating the *same* job id
        // is a legitimate idempotent replay, not a failure.)
        assertThatThrownBy(() -> backend.begin(job(700, 705, (byte) 73)).close())
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("overlaps committed coverage");
        for (var gate : backend.resourceDiagnostics().gates()) {
            assertThat(gate.inUse()).as("gate %s leaked a permit", gate.name()).isZero();
        }
        // The backend still works after the failure, proving nothing was leaked.
        try (var write = backend.begin(job(702, 703, (byte) 72))) {
            write.commit();
        }
        assertThat(backend.coverage(ArchiveDatasetId.TRANSACTION).completeRanges()).isNotEmpty();
    }

    @Test
    void mismatchedProjectionAndIdentityAreNonRetryableFatalErrors() {
        var backend = (DuckLakeHistoryArchiveBackend) backend();
        ArchiveJob foreign = ArchiveJob.deterministic(new ArchiveNetworkIdentity(999, "other-genesis"),
                ArchiveDatasetId.TRANSACTION,
                ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).projectionVersion(),
                new BlockRange(0, 1), new ArchiveRangeAnchor(0, new byte[32], 10, new byte[32]), "fixture-v1");
        assertThatThrownBy(() -> backend.begin(foreign))
                .isInstanceOf(ArchiveFatalException.class);
        for (var gate : backend.resourceDiagnostics().gates()) {
            assertThat(gate.inUse()).as("gate %s leaked a permit", gate.name()).isZero();
        }
    }

    @Test
    void concurrentProjectionsSerializeOnOneWriterWithoutLosingOrDuplicatingCoverage() throws Exception {
        var backend = (DuckLakeHistoryArchiveBackend) backend();
        int projections = 4;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(projections);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int index = 0; index < projections; index++) {
            long from = 1000L + index * 10L;
            byte marker = (byte) (40 + index);
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    ArchiveJob job = job(from, from + 9, marker);
                    try (var write = backend.begin(job)) {
                        write.append(row(job, marker, from + 9));
                        write.commit();
                    }
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();

        var ranges = backend.coverage(ArchiveDatasetId.TRANSACTION).completeRanges();
        // One merged run: every concurrent commit landed exactly once, in order.
        assertThat(ranges).contains(new BlockRange(1000, 1000 + projections * 10L - 1));
        for (var gate : backend.resourceDiagnostics().gates()) {
            assertThat(gate.inUse()).as("gate %s leaked a permit", gate.name()).isZero();
            assertThat(gate.waiters()).as("gate %s left a waiter", gate.name()).isZero();
        }
    }

    @Test
    void catalogBackupDrainsReadersFirstAndReleasesBothLocksOnEveryPath() throws Exception {
        var backend = (DuckLakeHistoryArchiveBackend) backend();
        Path target = temp.resolve("backup/catalog-copy.sqlite");

        assertThat(backend.backupCatalog(target)).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(Files.size(target)).isPositive();
        for (var gate : backend.resourceDiagnostics().gates()) {
            assertThat(gate.inUse()).as("gate %s leaked after backup", gate.name()).isZero();
        }

        // A rejected target must release the reader gate and writer too.
        assertThatThrownBy(() -> backend.backupCatalog(config().catalogPath()))
                .isInstanceOf(IllegalArgumentException.class);
        for (var gate : backend.resourceDiagnostics().gates()) {
            assertThat(gate.inUse()).as("gate %s leaked after a rejected backup", gate.name()).isZero();
        }
        // Normal work still proceeds, proving neither lock was stranded.
        try (var write = backend.begin(job(800, 809, (byte) 80))) {
            write.commit();
        }
        try (var reader = backend.openReadSession()) {
            assertThat(reader.generation()).isNotNegative();
        }
    }

    @Test
    void aStuckWaiterMustNotReleaseTheActiveWritersPermit() throws Exception {
        // Regression: the writer ticket used to live in one backend-wide field,
        // so a caller whose acquisition timed out released whichever ticket was
        // there -- handing the active writer's permit to a third operation.
        var shortStuck = new DuckLakeArchiveConfig(temp.resolve("stuck-catalog.sqlite"),
                temp.resolve("stuck-data"), Duration.ofSeconds(5), 10, 10,
                16L * 1024 * 1024, 10_000, Duration.ofHours(168), Duration.ofHours(24),
                new ArchiveWaitPolicy(
                        Duration.ofMillis(50), Duration.ofMillis(300)));
        try (var backend = DuckLakeHistoryArchiveBackend.open(identity(UUID.randomUUID()), shortStuck,
                DuckDbManagerConfig.defaults(temp.resolve("stuck-tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")), shortStuck.waitPolicy())) {

            ArchiveJob held = job(0, 9, (byte) 1);
            var active = backend.begin(held);   // holds the writer for the whole test
            try {
                // A second mutation cannot get the writer and hits its stuck threshold.
                assertThatThrownBy(() -> backend.maintain(
                        new ArchiveMaintenanceBudget(Duration.ofSeconds(2), 1024L * 1024)))
                        .isInstanceOf(ArchiveStuckOperationException.class);

                // The active writer must still hold its permit after that failure.
                var writerGate = backend.resourceDiagnostics().gates().stream()
                        .filter(gate -> gate.name().equals("ducklake-writer")).findFirst().orElseThrow();
                assertThat(writerGate.inUse())
                        .as("the timed-out waiter released the active writer's permit")
                        .isEqualTo(1);

                // And no third mutation may enter while the first is still open.
                assertThatThrownBy(() -> backend.invalidate(ArchiveDatasetId.TRANSACTION, new BlockRange(50, 59)))
                        .isInstanceOf(ArchiveStuckOperationException.class);
                assertThat(backend.resourceDiagnostics().gates().stream()
                        .filter(gate -> gate.name().equals("ducklake-writer")).findFirst().orElseThrow().inUse())
                        .isEqualTo(1);

                active.append(row(held, (byte) 1, 9));
                active.commit();
            } finally {
                active.close();
            }

            // Exactly one permit was released: the writer is free and usable again.
            assertThat(backend.resourceDiagnostics().gates().stream()
                    .filter(gate -> gate.name().equals("ducklake-writer")).findFirst().orElseThrow().inUse())
                    .isZero();
            try (var next = backend.begin(job(10, 19, (byte) 2))) {
                next.commit();
            }
        }
    }

    @Test
    void requestCapacitySaturationFailsFastAndLeavesBackendHealthUntouched() throws Exception {
        // Ordinary API saturation must not persist as DEGRADED health after the
        // burst clears; only real read failures degrade the backend.
        Duration requestBound = Duration.ofSeconds(1);
        var config = new DuckLakeArchiveConfig(temp.resolve("sat-catalog.sqlite"),
                temp.resolve("sat-data"), requestBound, 10, 10,
                16L * 1024 * 1024, 10_000, Duration.ofHours(168), Duration.ofHours(24),
                new ArchiveWaitPolicy(Duration.ofMillis(200), Duration.ofMinutes(5)));
        try (var backend = DuckLakeHistoryArchiveBackend.open(identity(UUID.randomUUID()), config,
                DuckDbManagerConfig.defaults(temp.resolve("sat-tmp")),
                new PackagedDuckDbExtensionLoader(temp.resolve("extensions")), config.waitPolicy())) {

            assertThat(backend.health().status()).isEqualTo(ArchiveHealth.Status.HEALTHY);
            int permits = DuckDbManagerConfig.defaults(temp.resolve("sat-tmp")).maxConcurrentQueries();
            List<com.bloxbean.cardano.yano.archive.api.ArchiveReadSession> held = new ArrayList<>();
            try {
                for (int index = 0; index < permits; index++) held.add(backend.openReadSession());

                long startedAt = System.nanoTime();
                assertThatThrownBy(backend::openReadSession)
                        .isInstanceOf(ArchiveStoreException.class)
                        .hasMessageContaining("saturated");
                Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
                assertThat(waited).as("request waited %s against a %s bound", waited, requestBound)
                        .isLessThan(requestBound.multipliedBy(4));

                // The whole point: saturation is contention, not ill health.
                assertThat(backend.health().status()).isEqualTo(ArchiveHealth.Status.HEALTHY);
            } finally {
                held.forEach(com.bloxbean.cardano.yano.archive.api.ArchiveReadSession::close);
            }

            // Capacity is back and the archive is still usable and healthy.
            try (var reader = backend.openReadSession()) {
                assertThat(reader.generation()).isNotNegative();
            }
            assertThat(backend.health().status()).isEqualTo(ArchiveHealth.Status.HEALTHY);
        }
    }

    @Test
    void onlyABoundedRequestTimeoutIsTreatedAsCapacitySaturation() {
        var capacity = new DuckDbCapacityTimeoutException("timed out waiting for a DuckDB query slot",
                new ArchiveStuckOperationException("duckdb-total", "read-session",
                        Duration.ofSeconds(1), "held by read-session"));
        assertThat(DuckLakeHistoryArchiveBackend.isRequestCapacityTimeout(capacity)).isTrue();
        assertThat(DuckLakeHistoryArchiveBackend.isRequestCapacityTimeout(
                new ArchiveStoreException("wrapped", capacity))).isTrue();

        // A worker-path stuck breach exceeded the operator threshold: a real problem.
        assertThat(DuckLakeHistoryArchiveBackend.isRequestCapacityTimeout(
                new ArchiveStuckOperationException("ducklake-writer", "begin", Duration.ofMinutes(5), "")))
                .isFalse();
        assertThat(DuckLakeHistoryArchiveBackend.isRequestCapacityTimeout(
                new java.sql.SQLException("catalog corruption"))).isFalse();
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
                ArchiveDatasetId.TRANSACTION,
                com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas
                        .schema(ArchiveDatasetId.TRANSACTION).projectionVersion(), new BlockRange(from, to),
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
