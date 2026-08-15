package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.StatefulBlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockArchiveWorkerTest {
    @TempDir Path temp;
    @Test
    void commitsBoundedCanonicalRangeThenPersistsCursorBeforeLeaseRelease() {
        FixtureSource source = new FixtureSource(0, 4);
        RecordingBackend backend = new RecordingBackend();
        MemoryProgress progress = new MemoryProgress();
        ArchiveWorkerMetrics metrics = new ArchiveWorkerMetrics();
        BlockArchiveWorker<String> worker = worker(source, backend, progress, metrics, () -> 0);

        assertThat(worker.runBatch(dataset(), 0, 4)).isEqualTo(1);
        assertThat(backend.rows).hasSize(2);
        assertThat(progress.value.orElseThrow().coordinate()).isEqualTo(1);
        assertThat(source.leaseClosed).isTrue();
        assertThat(metrics.dataset(ArchiveDatasetId.TRANSACTION).get(ArchiveTrack.BACKFILL).lag()).isEqualTo(3);
    }

    @Test
    void coreLagPausesWithoutReadingOrLeasingBodies() {
        FixtureSource source = new FixtureSource(0, 4);
        BlockArchiveWorker<String> worker = worker(source, new RecordingBackend(), new MemoryProgress(),
                new ArchiveWorkerMetrics(), () -> 100);
        assertThat(worker.runBatch(dataset(), 0, 4)).isEqualTo(-1);
        assertThat(source.reads).isZero();
        assertThat(source.leases).isZero();
    }

    @Test
    void retriesASmallerCompleteRangeWhenRowsExceedTheBatchLimit() {
        FixtureSource source = new FixtureSource(0, 1);
        RecordingBackend backend = new RecordingBackend();
        MemoryProgress progress = new MemoryProgress();
        ArchiveWorkerMetrics metrics = new ArchiveWorkerMetrics();
        ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 2, 3, 5);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return 1; }
            public long targetBlock() { return 1; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source,
                backend, progress, config, sync, metrics, Duration.ofMinutes(1));
        BlockArchiveDataset<String> twoRowsPerBlock = new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
            public int projectionVersion() { return 1; }
            public void derive(ArchiveJob job, BlockSourceContext<String> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                for (int index = 0; index < 2; index++) {
                    sink.accept(new ArchiveRow("chain_transaction", List.of(block.blockHash(),
                            block.blockHash(), block.blockNumber(), block.slot(), block.epoch(),
                            block.blockTime().getEpochSecond(), index, true, 0L, job.jobId())));
                }
            }
        };

        assertThat(worker.runBatch(twoRowsPerBlock, 0, 1)).isZero();
        assertThat(backend.rows).hasSize(2);
        assertThat(progress.value.orElseThrow().coordinate()).isZero();
        assertThat(source.leases).isEqualTo(2);
        assertThat(metrics.dataset(ArchiveDatasetId.TRANSACTION).get(ArchiveTrack.BACKFILL).state())
                .isEqualTo(ArchiveWorkerStatus.State.IDLE);
    }

    @Test
    void failsOnlyWhenOneBlockAloneExceedsTheRowLimit() {
        FixtureSource source = new FixtureSource(0, 0);
        RecordingBackend backend = new RecordingBackend();
        ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 1, 1, 5);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return 0; }
            public long targetBlock() { return 0; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source,
                backend, new MemoryProgress(), config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(1));
        BlockArchiveDataset<String> twoRows = new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
            public int projectionVersion() { return 1; }
            public void derive(ArchiveJob job, BlockSourceContext<String> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                sink.accept(new ArchiveRow("chain_transaction", List.of()));
                sink.accept(new ArchiveRow("chain_transaction", List.of()));
            }
        };

        assertThatThrownBy(() -> worker.runBatch(twoRows, 0, 0))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("row limit exceeded for block 0");
        assertThat(backend.committed).isFalse();
    }

    @Test
    void abortsStatefulAttemptBeforeRetryingTheSmallerRange() {
        FixtureSource source = new FixtureSource(0, 1);
        RecordingBackend backend = new RecordingBackend();
        ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 2, 3, 5);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return 1; }
            public long targetBlock() { return 1; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source,
                backend, new MemoryProgress(), config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(1));
        class StatefulFixture implements StatefulBlockArchiveDataset<String> {
            int begins;
            int aborts;
            int commits;
            int workingMutations;
            int committedMutations;
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.UTXO_HISTORY; }
            public int projectionVersion() { return 1; }
            public void beginBatch(ArchiveJob job, List<BlockSourceContext<String>> blocks) {
                begins++;
                assertThat(workingMutations).isZero();
            }
            public void derive(ArchiveJob job, BlockSourceContext<String> block,
                               java.util.function.Consumer<ArchiveRow> sink) {
                workingMutations++;
                sink.accept(new ArchiveRow("chain_transaction", List.of()));
                sink.accept(new ArchiveRow("chain_transaction", List.of()));
            }
            public void commitBatch(ArchiveReceipt receipt) {
                commits++;
                committedMutations = workingMutations;
                workingMutations = 0;
            }
            public void commitCoveredBatch(long backendGeneration) { throw new AssertionError(); }
            public void abortBatch() {
                aborts++;
                workingMutations = 0;
            }
        }
        StatefulFixture dataset = new StatefulFixture();

        assertThat(worker.runBatch(dataset, 0, 1)).isZero();
        assertThat(dataset.begins).isEqualTo(2);
        assertThat(dataset.aborts).isEqualTo(1);
        assertThat(dataset.commits).isEqualTo(1);
        assertThat(dataset.committedMutations).isEqualTo(1);
        assertThat(backend.rows).hasSize(2);
    }

    @Test
    void retriesASmallerRangeWhenTheBackendResourceBudgetIsExceeded() {
        FixtureSource source = new FixtureSource(0, 3);
        RecordingBackend backend = new RecordingBackend();
        backend.maximumRangeBlocks = 2;
        MemoryProgress progress = new MemoryProgress();
        ArchiveWorkerMetrics metrics = new ArchiveWorkerMetrics();
        ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 4, 100, 5);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return 3; }
            public long targetBlock() { return 3; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source,
                backend, progress, config, sync, metrics, Duration.ofMinutes(1));

        assertThat(worker.runBatch(dataset(), 0, 3)).isEqualTo(1);
        assertThat(backend.attempts).isEqualTo(2);
        assertThat(backend.rows).hasSize(2);
        assertThat(progress.value.orElseThrow().coordinate()).isEqualTo(1);
        assertThat(metrics.dataset(ArchiveDatasetId.TRANSACTION).get(ArchiveTrack.BACKFILL).state())
                .isEqualTo(ArchiveWorkerStatus.State.IDLE);
    }

    @Test
    void remembersSafeRangeAndProbesGrowthOnlyAfterRepeatedSuccess() {
        FixtureSource source = new FixtureSource(0, 15);
        RecordingBackend backend = new RecordingBackend();
        backend.maximumRangeBlocks = 2;
        MemoryProgress progress = new MemoryProgress();
        ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 8, 100, 5);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return 15; }
            public long targetBlock() { return 15; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source,
                backend, progress, config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(1));

        assertThat(worker.runBatch(dataset(), 0, 15)).isEqualTo(1);
        assertThat(backend.attempts).isEqualTo(3); // 8, 4, then 2 blocks.
        assertThat(worker.runBatch(dataset(), 0, 15)).isEqualTo(3);
        assertThat(worker.runBatch(dataset(), 0, 15)).isEqualTo(5);
        assertThat(backend.attempts).isEqualTo(5); // Reuses 2 without failed probes.

        backend.maximumRangeBlocks = 8;
        assertThat(worker.runBatch(dataset(), 0, 15)).isEqualTo(9);
        assertThat(backend.attempts).isEqualTo(6); // Three safe batches unlock a 4-block probe.
    }

    @Test
    void startsConservativelyAndGrowsSuccessfulBatchesToTheConfiguredCeiling() {
        FixtureSource source = new FixtureSource(0, 4_999);
        RecordingBackend backend = new RecordingBackend();
        MemoryProgress progress = new MemoryProgress();
        ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 1_000, 10_000, 5);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return 4_999; }
            public long targetBlock() { return 4_999; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source,
                backend, progress, config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(1));

        List<Long> committedEnds = new ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            committedEnds.add(worker.runBatch(dataset(), 0, 4_999));
        }

        assertThat(committedEnds).containsExactly(99L, 199L, 299L,
                499L, 699L, 899L, 1_299L, 1_699L, 2_099L, 2_899L);
    }

    @Test
    void changedCanonicalAnchorAbortsBackendAndDoesNotAdvanceCursor() {
        FixtureSource source = new FixtureSource(0, 0);
        source.changeAfterRead = true;
        RecordingBackend backend = new RecordingBackend();
        MemoryProgress progress = new MemoryProgress();
        assertThatThrownBy(() -> worker(source, backend, progress, new ArchiveWorkerMetrics(), () -> 0)
                .runBatch(dataset(), 0, 0))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("anchor changed");
        assertThat(backend.committed).isFalse();
        assertThat(progress.value).isEmpty();
        assertThat(source.leaseClosed).isTrue();
    }

    @Test
    void mixedForkInsideBatchFailsBeforeBackendWrite() {
        FixtureSource source = new FixtureSource(0, 1) {
            @Override public Optional<BlockSourceContext<String>> readCanonical(long block) {
                Optional<BlockSourceContext<String>> original = super.readCanonical(block);
                if (block != 1 || original.isEmpty()) return original;
                BlockSourceContext<String> value = original.orElseThrow();
                return Optional.of(new BlockSourceContext<>(value.blockNumber(), value.slot(), value.epoch(),
                        value.blockTime(), value.blockHash(), new byte[] {99}, value.block()));
            }
        };
        RecordingBackend backend = new RecordingBackend();

        assertThatThrownBy(() -> worker(source, backend, new MemoryProgress(),
                new ArchiveWorkerMetrics(), () -> 0).runBatch(dataset(), 0, 1))
                .isInstanceOf(ArchiveStoreException.class)
                .hasMessageContaining("mixed canonical forks");
        assertThat(backend.rows).isEmpty();
        assertThat(backend.committed).isFalse();
    }

    @Test
    void advancesDurableCursorThroughLivePromotedCoverageWithoutRewritingBackend() {
        FixtureSource source = new FixtureSource(0, 1);
        RecordingBackend backend = new RecordingBackend();
        backend.coverage = List.of(new BlockRange(0, 1));
        try (var progress = new com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore(
                temp.resolve("covered"))) {
            ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 2, 10, 5);
            CoreSyncView sync = new CoreSyncView() {
                public long localBlock() { return 1; }
                public long targetBlock() { return 1; }
            };
            var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source,
                    backend, progress, config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(1));

            assertThat(worker.runBatch(dataset(), 0, 1)).isEqualTo(1);
            assertThat(backend.rows).isEmpty();
            assertThat(progress.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.BACKFILL))
                    .get().extracting(ArchiveProgress::coordinate).isEqualTo(1L);
            assertThat(progress.oldestRequiredBlockNumber()).hasValue(2);
        }
    }

    @Test
    void prunedCommittedBodyDoesNotMasqueradeAsCanonicalRollback() {
        FixtureSource source = new FixtureSource(1, 1) {
            @Override public Optional<com.bloxbean.cardano.yano.api.CanonicalBlockReference> canonicalReference(
                    long block) {
                return block == 0 ? Optional.of(new com.bloxbean.cardano.yano.api.CanonicalBlockReference(
                        0, 0, new byte[] {1})) : super.canonicalReference(block);
            }
        };
        RecordingBackend backend = new RecordingBackend();
        MemoryProgress progress = new MemoryProgress();
        progress.value = Optional.of(new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.BACKFILL,
                0, 0, new byte[] {1}, 1));

        long result = worker(source, backend, progress, new ArchiveWorkerMetrics(), () -> 0)
                .runBatch(dataset(), 0, 0);

        assertThat(result).isZero();
        assertThat(backend.invalidated).isFalse();
    }

    private BlockArchiveWorker<String> worker(FixtureSource source, RecordingBackend backend,
                                              ArchiveProgressStore progress, ArchiveWorkerMetrics metrics,
                                              java.util.function.LongSupplier lag) {
        ArchiveWorkerConfig config = new ArchiveWorkerConfig(Duration.ofMillis(10), 2, 10, 5);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return 0; }
            public long targetBlock() { return lag.getAsLong(); }
        };
        return new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source, backend,
                progress, config, sync, metrics, Duration.ofMinutes(1));
    }

    private BlockArchiveDataset<String> dataset() {
        return new BlockArchiveDataset<>() {
            public ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
            public int projectionVersion() { return 1; }
            public void derive(ArchiveJob job, BlockSourceContext<String> source, java.util.function.Consumer<ArchiveRow> sink) {
                sink.accept(new ArchiveRow("chain_transaction", List.of(source.blockHash(), source.blockHash(),
                        source.blockNumber(), source.slot(), source.epoch(), source.blockTime().getEpochSecond(),
                        0, true, 0L, job.jobId())));
            }
        };
    }

    private static class FixtureSource implements BlockArchiveSource<String> {
        private final long first;
        private final long last;
        int reads;
        int leases;
        boolean leaseClosed;
        boolean changeAfterRead;

        private FixtureSource(long first, long last) { this.first = first; this.last = last; }

        public Optional<BlockSourceContext<String>> readCanonical(long block) {
            if (block < first || block > last) return Optional.empty();
            reads++;
            byte marker = (byte) (block + 1 + (changeAfterRead && reads > 1 ? 10 : 0));
            return Optional.of(new BlockSourceContext<>(block, block * 10, 0, Instant.EPOCH,
                    new byte[] {marker}, new byte[] {(byte) block}, "block-" + block));
        }

        public ArchiveSourceLease acquire(long startBlock, long endBlock, Instant expiresAt) {
            leases++;
            return new ArchiveSourceLease() {
                private final UUID id = UUID.randomUUID();
                public UUID leaseId() { return id; }
                public Instant expiresAt() { return expiresAt; }
                public ArchiveSourceLease renew(Instant expiry) { return this; }
                public void close() { leaseClosed = true; }
            };
        }
        public long earliestRetainedBody() { return first; }
    }

    private static final class MemoryProgress implements ArchiveProgressStore {
        Optional<ArchiveProgress> value = Optional.empty();
        public Optional<ArchiveProgress> load(ArchiveDatasetId dataset, ArchiveTrack track) { return value; }
        public void save(ArchiveProgress progress, ArchiveReceipt receipt) { value = Optional.of(progress); }
    }

    private static final class RecordingBackend implements ArchiveBackend {
        final List<ArchiveRow> rows = new ArrayList<>();
        List<ArchiveRange> coverage = List.of();
        boolean committed;
        boolean invalidated;
        long maximumRangeBlocks = Long.MAX_VALUE;
        int attempts;
        public ArchiveIdentity identity() { return new ArchiveIdentity(UUID.randomUUID(), "fixture", 1, 1, "fixture"); }
        public ArchiveCapabilities capabilities() { return new ArchiveCapabilities(true, false, false, false, false); }
        public ArchiveWriteSession begin(ArchiveJob job) {
            List<ArchiveRow> pending = new ArrayList<>();
            return new ArchiveWriteSession() {
                public void append(ArchiveRow row) { pending.add(row); }
                public ArchiveReceipt commit() {
                    attempts++;
                    if (job.range().endInclusive() - job.range().startInclusive() + 1 > maximumRangeBlocks) {
                        throw new ArchiveBatchCapacityException("fixture capacity", new SQLException("capacity"));
                    }
                    committed = true;
                    rows.addAll(pending);
                    return new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(), job.projectionVersion(),
                            job.range(), job.anchors(), 1, Map.of("chain_transaction", (long) pending.size()), "digest", Instant.EPOCH);
                }
                public void close() { }
            };
        }
        public Optional<ArchiveReceipt> findReceipt(UUID jobId) { return Optional.empty(); }
        public ArchiveCoverage coverage(ArchiveDatasetId dataset) { return new ArchiveCoverage(dataset, 1, 1, coverage); }
        public ArchiveReadSession openReadSession() { return new ArchiveReadSession() {
            public long generation() { return 1; }
            public void close() { }
        }; }
        public void invalidate(ArchiveDatasetId dataset, ArchiveRange range) { invalidated = true; }
        public int invalidateEpochJobsAfterSlot(ArchiveDatasetId dataset, long rollbackSlot) { return 0; }
        public void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff) { }
        public void maintain(ArchiveMaintenanceBudget budget) { }
        public ArchiveHealth health() { return ArchiveHealth.healthy(); }
        public void close() { }
    }
}
