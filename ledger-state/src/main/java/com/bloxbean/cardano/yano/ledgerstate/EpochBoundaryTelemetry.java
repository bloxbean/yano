package com.bloxbean.cardano.yano.ledgerstate;

import org.slf4j.Logger;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Low-allocation phase telemetry for epoch-boundary attribution.
 *
 * <p>The telemetry is deliberately log/report based rather than coupled to a
 * metrics backend. This keeps the ledger module independent of Micrometer and
 * makes the same measurements available in JVM and native builds. External
 * one-second RSS sampling remains the acceptance source; the in-process RSS
 * value is populated on Linux from {@code /proc/self/status} and reported as
 * unavailable elsewhere.</p>
 */
public final class EpochBoundaryTelemetry {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    private EpochBoundaryTelemetry() {
    }

    public static BoundaryRun start(Logger log,
                                    int previousEpoch,
                                    int newEpoch,
                                    DefaultAccountStateStore store) {
        Supplier<ResourceSnapshot> sampler = () -> ResourceSnapshot.capture(store);
        return new BoundaryRun(log, previousEpoch, newEpoch, System::nanoTime, sampler);
    }

    static BoundaryRun start(Logger log,
                             int previousEpoch,
                             int newEpoch,
                             LongSupplier nanoTime,
                             Supplier<ResourceSnapshot> sampler) {
        return new BoundaryRun(log, previousEpoch, newEpoch, nanoTime, sampler);
    }

    public record RocksDbMemory(long blockCacheBytes,
                                long pinnedBlockCacheBytes,
                                long memtableBytes,
                                long tableReaderBytes,
                                long pendingCompactionBytes) {
        static final RocksDbMemory UNAVAILABLE = new RocksDbMemory(-1, -1, -1, -1, -1);
    }

    public record ResourceSnapshot(long heapUsedBytes,
                                   long heapCommittedBytes,
                                   long heapMaxBytes,
                                   long rssBytes,
                                   long processCpuNanos,
                                   long gcCount,
                                   long gcTimeMillis,
                                   RocksDbMemory rocksDb) {
        static ResourceSnapshot capture(DefaultAccountStateStore store) {
            try {
                var heap = MEMORY.getHeapMemoryUsage();
                return new ResourceSnapshot(
                        heap.getUsed(),
                        heap.getCommitted(),
                        heap.getMax(),
                        linuxRssBytes(),
                        sampleProcessCpuNanos(),
                        totalGcCount(),
                        totalGcTimeMillis(),
                        store != null ? store.captureEpochBoundaryRocksDbMemory() : RocksDbMemory.UNAVAILABLE);
            } catch (Throwable ignored) {
                return new ResourceSnapshot(-1, -1, -1, -1, -1, -1, -1,
                        RocksDbMemory.UNAVAILABLE);
            }
        }

        private static long sampleProcessCpuNanos() {
            try {
                var bean = ManagementFactory.getOperatingSystemMXBean();
                if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                    return os.getProcessCpuTime();
                }
            } catch (Throwable ignored) {
                // Management extensions are optional in native images.
            }
            return -1;
        }

        private static long totalGcCount() {
            long total = 0;
            boolean available = false;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long count = bean.getCollectionCount();
                if (count >= 0) {
                    total += count;
                    available = true;
                }
            }
            return available ? total : -1;
        }

        private static long totalGcTimeMillis() {
            long total = 0;
            boolean available = false;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                long time = bean.getCollectionTime();
                if (time >= 0) {
                    total += time;
                    available = true;
                }
            }
            return available ? total : -1;
        }

        private static long linuxRssBytes() {
            Path status = Path.of("/proc/self/status");
            if (!Files.isReadable(status)) return -1;
            try {
                for (String line : Files.readAllLines(status)) {
                    if (!line.startsWith("VmRSS:")) continue;
                    String[] fields = line.substring("VmRSS:".length()).trim().split("\\s+");
                    return Long.parseLong(fields[0]) * 1024L;
                }
            } catch (Exception ignored) {
                // External RSS sampling remains authoritative.
            }
            return -1;
        }
    }

    public record PhaseSummary(String phase,
                               String path,
                               long wallNanos,
                               long cpuNanos,
                               long gcCountDelta,
                               long gcTimeMillisDelta,
                               ResourceSnapshot before,
                               ResourceSnapshot after) {
    }

    public record BoundarySummary(int previousEpoch,
                                  int newEpoch,
                                  boolean success,
                                  long wallNanos,
                                  ResourceSnapshot start,
                                  ResourceSnapshot end,
                                  ResourceSnapshot peak,
                                  List<PhaseSummary> phases) {
        public BoundarySummary {
            phases = List.copyOf(phases);
        }
    }

    public static final class BoundaryRun {
        private final Logger log;
        private final int previousEpoch;
        private final int newEpoch;
        private final LongSupplier nanoTime;
        private final Supplier<ResourceSnapshot> sampler;
        private final long startedNanos;
        private final ResourceSnapshot start;
        private final Map<String, PhaseSummary> phases = new LinkedHashMap<>();
        private ResourceSnapshot peak;
        private BoundarySummary finished;

        private BoundaryRun(Logger log,
                            int previousEpoch,
                            int newEpoch,
                            LongSupplier nanoTime,
                            Supplier<ResourceSnapshot> sampler) {
            this.log = Objects.requireNonNull(log, "log");
            this.previousEpoch = previousEpoch;
            this.newEpoch = newEpoch;
            this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
            this.sampler = Objects.requireNonNull(sampler, "sampler");
            this.startedNanos = nanoTime.getAsLong();
            this.start = safeSample();
            this.peak = start;
            logSnapshot("start", "boundary", start);
        }

        public Phase phase(String phase) {
            return phase(phase, "default");
        }

        public Phase phase(String phase, String path) {
            return new Phase(this, phase, path, nanoTime.getAsLong(), safeSample());
        }

        public BoundarySummary finish(boolean success) {
            if (finished != null) return finished;
            ResourceSnapshot end = safeSample();
            updatePeak(end);
            long wallNanos = Math.max(0, nanoTime.getAsLong() - startedNanos);
            finished = new BoundarySummary(previousEpoch, newEpoch, success, wallNanos,
                    start, end, peak, new ArrayList<>(phases.values()));
            log.info("epoch_boundary_summary previous_epoch={} new_epoch={} success={} wall_ms={} "
                            + "peak_heap_used_bytes={} peak_heap_committed_bytes={} peak_rss_bytes={} "
                            + "peak_rocksdb_block_cache_bytes={} peak_rocksdb_pinned_bytes={} "
                            + "peak_rocksdb_memtable_bytes={} peak_rocksdb_table_reader_bytes={} "
                            + "peak_rocksdb_pending_compaction_bytes={} phases={}",
                    previousEpoch, newEpoch, success, nanosToMillis(wallNanos),
                    peak.heapUsedBytes(), peak.heapCommittedBytes(), peak.rssBytes(),
                    peak.rocksDb().blockCacheBytes(), peak.rocksDb().pinnedBlockCacheBytes(),
                    peak.rocksDb().memtableBytes(), peak.rocksDb().tableReaderBytes(),
                    peak.rocksDb().pendingCompactionBytes(), phases.size());
            return finished;
        }

        private ResourceSnapshot safeSample() {
            try {
                ResourceSnapshot sample = sampler.get();
                return sample != null ? sample : unavailableSnapshot();
            } catch (Throwable ignored) {
                return unavailableSnapshot();
            }
        }

        private void completePhase(String name,
                                   String path,
                                   long phaseStartedNanos,
                                   ResourceSnapshot before) {
            ResourceSnapshot after = safeSample();
            updatePeak(before);
            updatePeak(after);
            long wallNanos = Math.max(0, nanoTime.getAsLong() - phaseStartedNanos);
            long cpuNanos = delta(before.processCpuNanos(), after.processCpuNanos());
            long gcCountDelta = delta(before.gcCount(), after.gcCount());
            long gcTimeDelta = delta(before.gcTimeMillis(), after.gcTimeMillis());
            PhaseSummary summary = new PhaseSummary(name, path, wallNanos, cpuNanos,
                    gcCountDelta, gcTimeDelta, before, after);
            phases.put(name, summary);
            log.info("epoch_boundary_phase previous_epoch={} new_epoch={} phase={} path={} wall_ms={} "
                            + "cpu_ms={} heap_used_before_bytes={} heap_used_after_bytes={} "
                            + "heap_committed_after_bytes={} rss_after_bytes={} gc_count_delta={} "
                            + "gc_time_ms_delta={} rocksdb_block_cache_bytes={} rocksdb_pinned_bytes={} "
                            + "rocksdb_memtable_bytes={} rocksdb_table_reader_bytes={} "
                            + "rocksdb_pending_compaction_bytes={}",
                    previousEpoch, newEpoch, name, path, nanosToMillis(wallNanos),
                    nanosToMillis(cpuNanos), before.heapUsedBytes(), after.heapUsedBytes(),
                    after.heapCommittedBytes(), after.rssBytes(), gcCountDelta, gcTimeDelta,
                    after.rocksDb().blockCacheBytes(), after.rocksDb().pinnedBlockCacheBytes(),
                    after.rocksDb().memtableBytes(), after.rocksDb().tableReaderBytes(),
                    after.rocksDb().pendingCompactionBytes());
        }

        private void logSnapshot(String phase, String path, ResourceSnapshot sample) {
            log.info("epoch_boundary_phase previous_epoch={} new_epoch={} phase={} path={} "
                            + "heap_used_bytes={} heap_committed_bytes={} heap_max_bytes={} "
                            + "rss_bytes={} process_cpu_nanos={} gc_count={} gc_time_ms={} "
                            + "rocksdb_block_cache_bytes={} rocksdb_pinned_bytes={} "
                            + "rocksdb_memtable_bytes={} rocksdb_table_reader_bytes={} "
                            + "rocksdb_pending_compaction_bytes={}",
                    previousEpoch, newEpoch, phase, path,
                    sample.heapUsedBytes(), sample.heapCommittedBytes(), sample.heapMaxBytes(),
                    sample.rssBytes(), sample.processCpuNanos(), sample.gcCount(), sample.gcTimeMillis(),
                    sample.rocksDb().blockCacheBytes(), sample.rocksDb().pinnedBlockCacheBytes(),
                    sample.rocksDb().memtableBytes(), sample.rocksDb().tableReaderBytes(),
                    sample.rocksDb().pendingCompactionBytes());
        }

        private void updatePeak(ResourceSnapshot candidate) {
            peak = new ResourceSnapshot(
                    maxAvailable(peak.heapUsedBytes(), candidate.heapUsedBytes()),
                    maxAvailable(peak.heapCommittedBytes(), candidate.heapCommittedBytes()),
                    maxAvailable(peak.heapMaxBytes(), candidate.heapMaxBytes()),
                    maxAvailable(peak.rssBytes(), candidate.rssBytes()),
                    maxAvailable(peak.processCpuNanos(), candidate.processCpuNanos()),
                    maxAvailable(peak.gcCount(), candidate.gcCount()),
                    maxAvailable(peak.gcTimeMillis(), candidate.gcTimeMillis()),
                    new RocksDbMemory(
                            maxAvailable(peak.rocksDb().blockCacheBytes(), candidate.rocksDb().blockCacheBytes()),
                            maxAvailable(peak.rocksDb().pinnedBlockCacheBytes(), candidate.rocksDb().pinnedBlockCacheBytes()),
                            maxAvailable(peak.rocksDb().memtableBytes(), candidate.rocksDb().memtableBytes()),
                            maxAvailable(peak.rocksDb().tableReaderBytes(), candidate.rocksDb().tableReaderBytes()),
                            maxAvailable(peak.rocksDb().pendingCompactionBytes(), candidate.rocksDb().pendingCompactionBytes())));
        }

        private static ResourceSnapshot unavailableSnapshot() {
            return new ResourceSnapshot(-1, -1, -1, -1, -1, -1, -1,
                    RocksDbMemory.UNAVAILABLE);
        }

        private static long maxAvailable(long left, long right) {
            if (left < 0) return right;
            if (right < 0) return left;
            return Math.max(left, right);
        }

        private static long delta(long before, long after) {
            if (before < 0 || after < 0) return -1;
            return Math.max(0, after - before);
        }

        private static long nanosToMillis(long nanos) {
            return nanos < 0 ? -1 : nanos / 1_000_000L;
        }
    }

    public static final class Phase implements AutoCloseable {
        private final BoundaryRun owner;
        private final String name;
        private final String path;
        private final long startedNanos;
        private final ResourceSnapshot before;
        private boolean closed;

        private Phase(BoundaryRun owner,
                      String name,
                      String path,
                      long startedNanos,
                      ResourceSnapshot before) {
            this.owner = owner;
            this.name = Objects.requireNonNull(name, "name");
            this.path = Objects.requireNonNull(path, "path");
            this.startedNanos = startedNanos;
            this.before = before;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.completePhase(name, path, startedNanos, before);
        }
    }
}
