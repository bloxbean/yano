package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.StatefulBlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.CycleCachingBlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.OrderedPrefetchingBlockArchiveSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 2c benchmark: complete UTXO batch cycle, not decoder throughput
 * alone.
 *
 * <p><b>Scope limitation, stated deliberately.</b> Real mainnet CBOR bodies are not
 * available to this suite — both deployments hold their chainstate open and a
 * second process must never attach to it — so per-block decode cost is
 * <em>simulated</em> at a level calibrated to production measurement (UTXO ran at
 * ~32.8 ms per block wall clock, of which the archive write session was 4.1%).
 * The benchmark therefore measures how well the pipeline overlaps decode with
 * strictly sequential derivation, which is exactly the Phase 2c question, but it
 * is not a measurement of real CBOR throughput. Any projection drawn from it
 * inherits that caveat.
 */
class UtxoPrefetchBenchmarkTest {

    /** Calibrated from production: decode dominates, derivation and commit are small. */
    private static final long DECODE_MICROS = 4_000;
    private static final long DERIVE_MICROS = 700;
    private static final long COMMIT_MICROS = 1_300;
    private static final int BLOCKS = 240;
    private static final int RUNS = 3;

    private ExecutorService executor;

    @AfterEach
    void shutdown() {
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }

    private static void burn(long micros) {
        long deadline = System.nanoTime() + micros * 1_000;
        long sink = 0;
        while (System.nanoTime() < deadline) {
            // Real decoding is CPU-bound, so busy work models it better than sleep.
            sink += System.nanoTime() ^ sink;
        }
        if (sink == Long.MIN_VALUE) throw new IllegalStateException("unreachable");
    }

    private static final class DecodingSource implements BlockArchiveSource<String> {
        final AtomicLong decodeNanos = new AtomicLong();
        final AtomicLong decodes = new AtomicLong();

        @Override public Optional<BlockSourceContext<String>> readCanonical(long block) {
            if (block < 0 || block >= BLOCKS) return Optional.empty();
            long started = System.nanoTime();
            burn(DECODE_MICROS);
            decodeNanos.addAndGet(System.nanoTime() - started);
            decodes.incrementAndGet();
            return Optional.of(new BlockSourceContext<>(block, block * 10, 0, Instant.EPOCH,
                    new byte[]{(byte) (block + 1)}, new byte[]{(byte) block}, "block-" + block));
        }

        @Override public ArchiveSourceLease acquire(long start, long end, Instant expiry) {
            return new ArchiveSourceLease() {
                private final UUID id = UUID.randomUUID();
                public UUID leaseId() { return id; }
                public Instant expiresAt() { return expiry; }
                public ArchiveSourceLease renew(Instant value) { return this; }
                public void close() { }
            };
        }

        @Override public long earliestRetainedBody() { return 0; }
    }

    private static final class SequentialDataset implements StatefulBlockArchiveDataset<String> {
        final AtomicLong deriveNanos = new AtomicLong();
        private List<BlockSourceContext<String>> batch = List.of();
        private int index;

        public ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
        public int projectionVersion() { return 1; }
        public void beginBatch(ArchiveJob job, List<BlockSourceContext<String>> blocks) {
            batch = List.copyOf(blocks); index = 0;
        }
        public void derive(ArchiveJob job, BlockSourceContext<String> block,
                           java.util.function.Consumer<ArchiveRow> sink) {
            if (index >= batch.size() || batch.get(index).blockNumber() != block.blockNumber()) {
                throw new IllegalStateException("benchmark dataset consumed out of order");
            }
            index++;
            long started = System.nanoTime();
            burn(DERIVE_MICROS);
            deriveNanos.addAndGet(System.nanoTime() - started);
            sink.accept(new ArchiveRow("chain_transaction", List.of(block.blockHash(), block.blockHash(),
                    block.blockNumber(), block.slot(), 0L, 0L, 0, true, 0L, job.jobId())));
        }
        public void commitBatch(ArchiveReceipt receipt) { }
        public void commitCoveredBatch(long backendGeneration) { }
        public void abortBatch() { }
    }

    private static final class TimedBackend implements ArchiveBackend {
        final AtomicLong commitNanos = new AtomicLong();
        final List<ArchiveRow> rows = new ArrayList<>();
        public ArchiveIdentity identity() { return new ArchiveIdentity(UUID.randomUUID(), "fixture", 1, 1, "fixture"); }
        public ArchiveCapabilities capabilities() { return new ArchiveCapabilities(true, false, false, false, false); }
        public ArchiveWriteSession begin(ArchiveJob job) {
            List<ArchiveRow> pending = new ArrayList<>();
            return new ArchiveWriteSession() {
                public void append(ArchiveRow row) { pending.add(row); }
                public ArchiveReceipt commit() {
                    long started = System.nanoTime();
                    burn(COMMIT_MICROS);
                    commitNanos.addAndGet(System.nanoTime() - started);
                    rows.addAll(pending);
                    return new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(),
                            job.projectionVersion(), job.range(), job.anchors(), 1,
                            java.util.Map.of("chain_transaction", (long) pending.size()), "digest", Instant.EPOCH);
                }
                public void close() { }
            };
        }
        public Optional<ArchiveReceipt> findReceipt(UUID jobId) { return Optional.empty(); }
        public ArchiveCoverage coverage(ArchiveDatasetId dataset) { return new ArchiveCoverage(dataset, 1, 1, List.of()); }
        public ArchiveCoverage coverage(ArchiveReadSession session, ArchiveDatasetId dataset) { return coverage(dataset); }
        public Optional<ArchiveCommitBoundary> latestBlockBoundary(ArchiveReadSession session, ArchiveDatasetId dataset,
                BlockRange range, OptionalLong atOrBeforeSlot) { return Optional.empty(); }
        public ArchiveReadSession openReadSession() {
            return new ArchiveReadSession() { public long generation() { return 1; } public void close() { } };
        }
        public void invalidate(ArchiveDatasetId dataset, ArchiveRange range) { }
        public int invalidateEpochJobsAfterSlot(ArchiveDatasetId dataset, long rollbackSlot) { return 0; }
        public void applyRetention(ArchiveDatasetId dataset, ArchiveRetentionCutoff cutoff) { }
        public void maintain(ArchiveMaintenanceBudget budget) { }
        public ArchiveHealth health() { return ArchiveHealth.healthy(); }
        public void close() { }
    }

    private static final class MemoryProgress implements ArchiveProgressStore {
        Optional<ArchiveProgress> value = Optional.empty();
        public Optional<ArchiveProgress> load(ArchiveDatasetId dataset, ArchiveTrack track) { return value; }
        public void save(ArchiveProgress progress, ArchiveReceipt receipt) { value = Optional.of(progress); }
    }

    private record Result(String label, double seconds, double blocksPerSecond, double decodeSeconds,
                          double deriveSeconds, double commitSeconds, double reorderWaitSeconds,
                          long peakInFlightBlocks, long peakReservedBytes, long peakObservedBytes,
                          long rows) { }

    /** @param mode -1 = existing serial path with the cycle cache; otherwise prefetch parallelism */
    private Result runOnce(String label, int mode) {
        DecodingSource fixture = new DecodingSource();
        BlockArchiveSource<String> source;
        OrderedPrefetchingBlockArchiveSource<String> prefetching = null;
        CycleCachingBlockArchiveSource<String> cache = null;
        if (mode < 0) {
            cache = new CycleCachingBlockArchiveSource<>(fixture, BLOCKS * 2);
            cache.beginCycle();
            source = cache;
        } else {
            executor = Executors.newFixedThreadPool(mode);
            prefetching = new OrderedPrefetchingBlockArchiveSource<>(fixture, executor,
                    Math.max(mode * 2, 4), 512L * 1024 * 1024, 2L * 1024 * 1024,
                    ignored -> 1024L * 1024, Duration.ofSeconds(60));
            source = prefetching;
        }

        TimedBackend backend = new TimedBackend();
        MemoryProgress progress = new MemoryProgress();
        SequentialDataset dataset = new SequentialDataset();
        var config = new ArchiveWorkerConfig(Duration.ofMillis(10), BLOCKS, 1_000_000, false, 5, 1);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return BLOCKS; }
            public long targetBlock() { return BLOCKS; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source, backend,
                progress, config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(5));

        long started = System.nanoTime();
        worker.runBatch(dataset, 0, BLOCKS - 1);
        double seconds = (System.nanoTime() - started) / 1e9;
        if (cache != null) cache.endCycle();

        var stats = prefetching == null ? null : prefetching.stats();
        if (stats != null) {
            assertThat(stats.drainTimeouts()).isZero();
            assertThat(stats.underestimated()).as("estimate held for " + label).isFalse();
        }
        long processed = backend.rows.size();
        return new Result(label, seconds, processed / seconds,
                fixture.decodeNanos.get() / 1e9, dataset.deriveNanos.get() / 1e9,
                backend.commitNanos.get() / 1e9,
                stats == null ? 0 : stats.reorderWaitNanos() / 1e9,
                stats == null ? 0 : stats.peakInFlightBlocks(),
                stats == null ? 0 : stats.peakReservedBytes(),
                stats == null ? 0 : stats.peakObservedBytes(),
                backend.rows.size());
    }

    @Test
    void benchmarksCompleteUtxoCycleAcrossParallelism() {
        record Config(String label, int mode) { }
        List<Config> configs = List.of(
                new Config("serial + cycle cache", -1),
                new Config("prefetch p=1", 1),
                new Config("prefetch p=2", 2),
                new Config("prefetch p=4", 4));

        System.out.println("=== ADR-038 Phase 2c: complete UTXO batch cycle (adaptive batch, "
                + RUNS + " runs each; simulated decode " + DECODE_MICROS + "us/block) ===");
        System.out.printf(Locale.ROOT, "%-22s %8s %10s %9s %9s %9s %9s %7s%n",
                "config", "sec", "blocks/s", "decode", "derive", "commit", "reorder", "inflt");

        double serialBaseline = 0;
        double parallelOneRate = 0;
        for (Config config : configs) {
            List<Result> runs = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                runs.add(runOnce(config.label(), config.mode()));
                shutdown();
            }
            runs.sort(java.util.Comparator.comparingDouble(Result::seconds));
            Result median = runs.get(RUNS / 2);
            double spread = (runs.get(RUNS - 1).seconds() - runs.get(0).seconds()) / median.seconds();
            System.out.printf(Locale.ROOT, "%-22s %8.3f %10.1f %9.2f %9.2f %9.2f %9.2f %7d   (spread %.1f%%)%n",
                    median.label(), median.seconds(), median.blocksPerSecond(), median.decodeSeconds(),
                    median.deriveSeconds(), median.commitSeconds(), median.reorderWaitSeconds(),
                    median.peakInFlightBlocks(), 100 * spread);
            if (config.mode() < 0) serialBaseline = median.blocksPerSecond();
            if (config.mode() == 1) parallelOneRate = median.blocksPerSecond();
            long blocks = median.rows();
            for (Result run : runs) {
                assertThat(run.rows()).as("every run processed the same block count").isEqualTo(blocks);
            }
            assertThat(blocks).isPositive();
            assertThat(spread).as("repeatable within 35% for " + config.label()).isLessThan(0.35);
        }
        System.out.printf(Locale.ROOT, "prefetch-only gain (serial -> p=1): %.2fx%n",
                parallelOneRate / serialBaseline);
        assertThat(serialBaseline).isPositive();
    }
}
