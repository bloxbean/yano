package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.StatefulBlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.OrderedPrefetchingBlockArchiveSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 2c: running the real {@link BlockArchiveWorker} over the ordered
 * prefetching source must be byte-identical to running it serially.
 *
 * <p>The fixture dataset deliberately carries dependent state across and within
 * blocks — it spends outputs created by earlier transactions in the same block and
 * by preceding blocks — so any reordering, duplication or omission changes the
 * emitted rows and the digest. Equivalence of the rows is therefore evidence about
 * ordering, not merely about counts.
 */
class PrefetchSerialEquivalenceTest {

    private ExecutorService executor;

    @AfterEach
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Everything a run produces that must not vary with prefetching. */
    private record Outcome(List<String> rows, String digest, Map<String, Long> counts,
                           long cursor, List<String> stateOperations, List<String> undo) { }

    // ---------------- fixtures ----------------

    private static final class FixtureSource implements BlockArchiveSource<String> {
        private final long first;
        private final long last;
        private final AtomicInteger reads = new AtomicInteger();
        private final long delayFirstBlockMillis;

        FixtureSource(long first, long last, long delayFirstBlockMillis) {
            this.first = first; this.last = last; this.delayFirstBlockMillis = delayFirstBlockMillis;
        }

        @Override public Optional<BlockSourceContext<String>> readCanonical(long block) {
            if (block < first || block > last) return Optional.empty();
            reads.incrementAndGet();
            // Force later blocks to complete before the first when requested.
            if (block == first && delayFirstBlockMillis > 0) {
                try { Thread.sleep(delayFirstBlockMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Optional.of(new BlockSourceContext<>(block, block * 10, block / 100, Instant.EPOCH,
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

        @Override public long earliestRetainedBody() { return first; }
    }

    /**
     * Stateful dataset with genuine intra-block and cross-block dependencies: each
     * block creates outputs and spends one created earlier in the same block plus
     * one from the preceding block, and every emitted row encodes the resolved
     * state at that point.
     */
    private static final class DependencyTrackingDataset implements StatefulBlockArchiveDataset<String> {
        private final List<String> operations = new ArrayList<>();
        private final List<String> undo = new ArrayList<>();
        private final Map<String, Long> unspent = new LinkedHashMap<>();
        private List<BlockSourceContext<String>> batch = List.of();
        private int index;
        private long resolvedCounter;

        @Override public ArchiveDatasetId dataset() { return ArchiveDatasetId.TRANSACTION; }
        @Override public int projectionVersion() { return 1; }

        @Override public void beginBatch(ArchiveJob job, List<BlockSourceContext<String>> blocks) {
            operations.add("beginBatch:" + blocks.size());
            batch = List.copyOf(blocks);
            index = 0;
        }

        @Override public void derive(ArchiveJob job, BlockSourceContext<String> block,
                                     java.util.function.Consumer<ArchiveRow> sink) {
            if (index >= batch.size() || batch.get(index).blockNumber() != block.blockNumber()) {
                throw new IllegalStateException("dependency dataset consumed out of canonical order at "
                        + block.blockNumber());
            }
            index++;
            long number = block.blockNumber();
            operations.add("derive:" + number);

            // Two transactions per block; tx1 creates an output that tx2 spends
            // (same-block parent/child), and tx2 also spends the previous block's
            // output (cross-block spend).
            String created = "out-" + number + "-0";
            unspent.put(created, number);
            resolvedCounter++;
            sink.accept(row(job, number, 0, "create:" + created + "@" + resolvedCounter));

            Long sameBlock = unspent.remove(created);
            Long crossBlock = unspent.remove("out-" + (number - 1) + "-1");
            resolvedCounter++;
            sink.accept(row(job, number, 1, "spend:sameBlock=" + sameBlock
                    + ",crossBlock=" + crossBlock + "@" + resolvedCounter));
            undo.add("undo:" + number + ":" + sameBlock + ":" + crossBlock);

            String carried = "out-" + number + "-1";
            unspent.put(carried, number);
        }

        private static ArchiveRow row(ArchiveJob job, long block, int txIndex, String detail) {
            return new ArchiveRow("chain_transaction", List.of(new byte[]{(byte) block}, new byte[]{(byte) block},
                    block, block * 10, 0L, 0L, txIndex, true, 0L, job.jobId(), detail));
        }

        @Override public void commitBatch(ArchiveReceipt receipt) { operations.add("commitBatch"); }
        @Override public void commitCoveredBatch(long backendGeneration) { operations.add("commitCovered"); }
        @Override public void abortBatch() { operations.add("abortBatch"); }
    }

    private static final class MemoryProgress implements ArchiveProgressStore {
        Optional<ArchiveProgress> value = Optional.empty();
        public Optional<ArchiveProgress> load(ArchiveDatasetId dataset, ArchiveTrack track) { return value; }
        public void save(ArchiveProgress progress, ArchiveReceipt receipt) { value = Optional.of(progress); }
    }

    private static final class RecordingBackend implements ArchiveBackend {
        final List<ArchiveRow> rows = new ArrayList<>();
        public ArchiveIdentity identity() { return new ArchiveIdentity(UUID.randomUUID(), "fixture", 1, 1, "fixture"); }
        public ArchiveCapabilities capabilities() { return new ArchiveCapabilities(true, false, false, false, false); }
        public ArchiveWriteSession begin(ArchiveJob job) {
            List<ArchiveRow> pending = new ArrayList<>();
            return new ArchiveWriteSession() {
                public void append(ArchiveRow row) { pending.add(row); }
                public ArchiveReceipt commit() {
                    rows.addAll(pending);
                    return new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(),
                            job.projectionVersion(), job.range(), job.anchors(), 1,
                            Map.of("chain_transaction", (long) pending.size()), "digest", Instant.EPOCH);
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

    // ---------------- harness ----------------

    private Outcome run(int parallelism, long blocks, long delayFirstBlockMillis) {
        FixtureSource fixture = new FixtureSource(0, blocks - 1, delayFirstBlockMillis);
        BlockArchiveSource<String> source = fixture;
        OrderedPrefetchingBlockArchiveSource<String> prefetching = null;
        if (parallelism > 0) {
            executor = Executors.newFixedThreadPool(parallelism);
            prefetching = new OrderedPrefetchingBlockArchiveSource<>(fixture, executor,
                    Math.max(2, parallelism * 2), 1024L * 1024, 4096,
                    ignored -> 4096L, Duration.ofSeconds(30));
            source = prefetching;
        }

        RecordingBackend backend = new RecordingBackend();
        MemoryProgress progress = new MemoryProgress();
        DependencyTrackingDataset dataset = new DependencyTrackingDataset();
        var config = new ArchiveWorkerConfig(Duration.ofMillis(10), (int) blocks, 100_000, false, 5, 1);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return blocks; }
            public long targetBlock() { return blocks; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source, backend,
                progress, config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(1));

        worker.runBatch(dataset, 0, blocks - 1);

        List<String> rendered = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }
        for (ArchiveRow row : backend.rows) {
            String rendering = row.table() + '|' + row.values().stream()
                    .map(value -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value))
                    .reduce((a, b) -> a + ',' + b).orElse("");
            rendered.add(rendering);
            counts.merge(row.table(), 1L, Long::sum);
            digest.update(rendering.getBytes(StandardCharsets.UTF_8));
        }
        if (prefetching != null) {
            assertThat(prefetching.stats().drainTimeouts()).as("no drain timeout in equivalence run").isZero();
        }
        return new Outcome(rendered, HexFormat.of().formatHex(digest.digest()), counts,
                progress.value.map(ArchiveProgress::coordinate).orElse(-1L),
                List.copyOf(dataset.operations), List.copyOf(dataset.undo));
    }

    // ---------------- tests ----------------

    @Test
    void serialAndParallelProduceByteIdenticalResults() {
        Outcome serial = run(0, 60, 0);
        shutdown();
        Outcome one = run(1, 60, 0);
        shutdown();
        Outcome two = run(2, 60, 0);
        shutdown();
        Outcome four = run(4, 60, 0);

        assertThat(serial.rows()).isNotEmpty();
        for (Outcome candidate : List.of(one, two, four)) {
            assertThat(candidate.rows()).as("rows and their order").isEqualTo(serial.rows());
            assertThat(candidate.digest()).as("ordered digest").isEqualTo(serial.digest());
            assertThat(candidate.counts()).as("per-table counts").isEqualTo(serial.counts());
            assertThat(candidate.cursor()).as("cursor").isEqualTo(serial.cursor());
            assertThat(candidate.stateOperations()).as("stateful call sequence").isEqualTo(serial.stateOperations());
            assertThat(candidate.undo()).as("undo records").isEqualTo(serial.undo());
        }
    }

    @Test
    void outOfOrderDecodeCompletionStillMatchesSerialExactly() {
        Outcome serial = run(0, 40, 0);
        shutdown();
        // The first block decodes slowly, so later blocks finish well ahead of it.
        Outcome reordered = run(4, 40, 120);

        assertThat(reordered.rows()).isEqualTo(serial.rows());
        assertThat(reordered.digest()).isEqualTo(serial.digest());
        assertThat(reordered.stateOperations()).isEqualTo(serial.stateOperations());
        assertThat(reordered.undo()).isEqualTo(serial.undo());
    }

    @Test
    void sameBlockAndCrossBlockDependenciesResolveIdentically() {
        Outcome serial = run(0, 30, 0);
        shutdown();
        Outcome parallel = run(4, 30, 60);

        // Same-block child sees its parent's output; cross-block spend sees the
        // preceding block's carried output. Both are encoded in the row text.
        assertThat(serial.rows()).anySatisfy(row ->
                assertThat(row).contains("spend:sameBlock=5,crossBlock=4"));
        assertThat(parallel.rows()).isEqualTo(serial.rows());
        assertThat(parallel.rows().stream().filter(r -> r.contains("crossBlock=null")).count())
                .as("only the first block has no predecessor output").isEqualTo(1);
    }

    @Test
    void repeatedParallelRunsAreDeterministic() {
        Outcome first = run(4, 45, 30);
        shutdown();
        Outcome second = run(4, 45, 30);
        shutdown();
        Outcome third = run(4, 45, 30);

        assertThat(second.digest()).isEqualTo(first.digest());
        assertThat(third.digest()).isEqualTo(first.digest());
        assertThat(second.rows()).isEqualTo(first.rows());
        assertThat(third.rows()).isEqualTo(first.rows());
    }
}
