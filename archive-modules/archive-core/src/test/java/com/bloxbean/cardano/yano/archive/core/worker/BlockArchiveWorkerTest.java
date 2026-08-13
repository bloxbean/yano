package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockArchiveWorkerTest {
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

    private BlockArchiveWorker<String> worker(FixtureSource source, RecordingBackend backend,
                                              MemoryProgress progress, ArchiveWorkerMetrics metrics,
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
            public void derive(BlockSourceContext<String> source, java.util.function.Consumer<ArchiveRow> sink) {
                sink.accept(new ArchiveRow("chain_transaction", List.of(source.blockHash(), source.blockHash(),
                        source.blockNumber(), source.slot(), source.epoch(), source.blockTime().getEpochSecond(),
                        0, true, 0L, UUID.nameUUIDFromBytes(source.blockHash()))));
            }
        };
    }

    private static final class FixtureSource implements BlockArchiveSource<String> {
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
        boolean committed;
        public ArchiveIdentity identity() { return new ArchiveIdentity(UUID.randomUUID(), "fixture", 1, 1, "fixture"); }
        public ArchiveCapabilities capabilities() { return new ArchiveCapabilities(true, false, false, false, false); }
        public ArchiveWriteSession begin(ArchiveJob job) {
            return new ArchiveWriteSession() {
                public void append(ArchiveRow row) { rows.add(row); }
                public ArchiveReceipt commit() {
                    committed = true;
                    return new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(), job.projectionVersion(),
                            job.range(), job.anchors(), 1, Map.of("chain_transaction", (long) rows.size()), "digest", Instant.EPOCH);
                }
                public void close() { }
            };
        }
        public Optional<ArchiveReceipt> findReceipt(UUID jobId) { return Optional.empty(); }
        public ArchiveCoverage coverage(ArchiveDatasetId dataset) { return new ArchiveCoverage(dataset, 1, 0, List.of()); }
        public ArchiveReadSession openReadSession() { throw new UnsupportedOperationException(); }
        public void invalidate(ArchiveDatasetId dataset, ArchiveRange range) { }
        public void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff) { }
        public void maintain(ArchiveMaintenanceBudget budget) { }
        public ArchiveHealth health() { return ArchiveHealth.healthy(); }
        public void close() { }
    }
}
