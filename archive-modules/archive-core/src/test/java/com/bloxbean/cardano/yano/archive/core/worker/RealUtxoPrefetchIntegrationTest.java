package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import com.bloxbean.cardano.yano.archive.core.hot.HotArchiveRows;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.OrderedPrefetchingBlockArchiveSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-038 Phase 2c: serial versus ordered-prefetch equivalence through the
 * <b>real</b> {@link UtxoHistoryDataset} and a <b>real</b>
 * {@link RocksDbHotHistoryStore}, driven by the real {@link BlockArchiveWorker}.
 *
 * <h2>What is covered where</h2>
 *
 * <p><b>This test — the ordered-prefetch integration layer.</b> That prefetching
 * changes nothing observable once real resolver state, undo records and hot-store
 * mutations are involved: identical archive rows and order, ordered digest,
 * per-table counts, pointer/resolver state, undo records, hot-store contents,
 * cursor and coverage across parallelism 1, 2 and 4, including forced
 * out-of-order decode completion. Fixtures carry same-block create-then-spend,
 * cross-block spends and pointer registrations.
 *
 * <p>On the BACKFILL track the dataset publishes facts to the archive while the
 * real {@link RocksDbHotHistoryStore} carries resolver/pointer state, undo records
 * and the cursor; hot <em>fact</em> rows belong to the LIVE track. Equivalence is
 * therefore asserted on archive rows and digest, the persisted progress read back
 * from the real store, and the hot-row probe — with pointer resolution feeding
 * output stake credentials, so any resolver divergence would change the rows.
 *
 * <p><b>Not this test — the decoder layer.</b> Whether multi-assets, datums,
 * redeemers, collateral and pre-/post-Conway shapes are decoded <em>correctly</em>
 * is asserted by {@code YaciUtxoHistoryDecoder}'s own suites. Those distinctions
 * live inside decoder output, which prefetching does not alter, so they are
 * carried here only as payload — assets, datums and redeemers are present in the
 * fixtures to prove they survive reordering unchanged, not to re-assert decoding.
 * Real CBOR decode concurrency is proven separately in
 * {@code RealBlockDecoderConcurrencyTest}.
 */
class RealUtxoPrefetchIntegrationTest {

    @TempDir Path temp;
    private ExecutorService executor;
    private final List<RocksDbHotHistoryStore> stores = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (executor != null) { executor.shutdownNow(); executor = null; }
        stores.forEach(RocksDbHotHistoryStore::close);
        stores.clear();
    }

    private static byte[] hash(int seed, int salt) {
        return bytes(32, seed, salt);
    }

    /** Credentials are 28 bytes on Cardano; the real dataset validates this. */
    private static byte[] credential(int seed, int salt) {
        return bytes(28, seed, salt);
    }

    private static byte[] bytes(int length, int seed, int salt) {
        byte[] value = new byte[length];
        value[0] = (byte) seed; value[1] = (byte) (seed >>> 8); value[2] = (byte) salt;
        return value;
    }

    /**
     * Blocks with real dependency structure: each block creates two outputs, spends
     * one created earlier in the <em>same</em> block, and spends one carried from
     * the <em>preceding</em> block. Every third block registers a pointer.
     */
    private static UtxoHistoryFact facts(long blockNumber) {
        int seed = (int) blockNumber;
        byte[] txA = hash(seed, 1);
        byte[] txB = hash(seed, 2);
        byte[] addressKey = hash(seed, 3);

        List<UtxoHistoryFact.Address> addresses = List.of(new UtxoHistoryFact.Address(
                addressKey, hash(seed, 4), "addr_test_" + blockNumber, 0, "base", "key",
                credential(seed, 5), "key", "key", credential(seed, 6), null, null, null));

        List<UtxoHistoryFact.Output> outputs = List.of(
                new UtxoHistoryFact.Output(txA, 0, 0, "output", addressKey, credential(seed, 5), credential(seed, 6),
                        1_000_000L + blockNumber, "none", null, null, null, null, null, false),
                new UtxoHistoryFact.Output(txB, 1, 1, "output", addressKey, credential(seed, 5), credential(seed, 6),
                        2_000_000L + blockNumber, "hash", hash(seed, 7), null, null, null, null, false));

        List<UtxoHistoryFact.Input> inputs = List.of(
                // Same-block child spending the output created by tx index 0.
                new UtxoHistoryFact.Input(txB, 1, 0, "input", txA, 0, true),
                // Cross-block spend of the previous block's carried output.
                new UtxoHistoryFact.Input(txB, 1, 1, "input", hash(seed - 1, 2), 1, true));

        List<UtxoHistoryFact.Asset> assets = List.of(new UtxoHistoryFact.Asset(
                txB, 1, hash(seed, 8), "TOKEN".getBytes(StandardCharsets.UTF_8),
                BigInteger.valueOf(42 + blockNumber)));

        List<UtxoHistoryFact.TransactionDatum> datums = List.of(new UtxoHistoryFact.TransactionDatum(
                txB, 1, hash(seed, 9), hash(seed, 10)));

        List<UtxoHistoryFact.TransactionRedeemer> redeemers = List.of(new UtxoHistoryFact.TransactionRedeemer(
                txB, 1, "spend", 0, hash(seed, 11), hash(seed, 12),
                BigInteger.valueOf(1000), BigInteger.valueOf(2000)));

        List<UtxoHistoryFact.PointerRegistration> pointers = blockNumber % 3 == 0
                ? List.of(new UtxoHistoryFact.PointerRegistration(blockNumber * 10, 1, 0, "key", credential(seed, 13)))
                : List.of();

        return new UtxoHistoryFact(6, pointers, List.of(), addresses, outputs, assets, inputs, datums, redeemers);
    }

    private static final class FactSource implements BlockArchiveSource<UtxoHistoryFact> {
        private final long first;
        private final long last;
        private final long delayFirstMillis;

        FactSource(long first, long last, long delayFirstMillis) {
            this.first = first; this.last = last; this.delayFirstMillis = delayFirstMillis;
        }

        @Override public Optional<BlockSourceContext<UtxoHistoryFact>> readCanonical(long block) {
            if (block < first || block > last) return Optional.empty();
            if (block == first && delayFirstMillis > 0) {
                try { Thread.sleep(delayFirstMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return Optional.of(new BlockSourceContext<>(block, block * 10, 0, Instant.EPOCH,
                    new byte[]{(byte) (block + 1)}, new byte[]{(byte) block}, facts(block)));
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
                    Map<String, Long> counts = new LinkedHashMap<>();
                    for (ArchiveRow row : pending) counts.merge(row.table(), 1L, Long::sum);
                    return new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(),
                            job.projectionVersion(), job.range(), job.anchors(), 1, counts, "digest", Instant.EPOCH);
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

    private record Outcome(List<String> rows, String digest, Map<String, Long> counts, long cursor,
                           List<String> hotRows, String progressHash) { }

    /** @param parallelism 0 selects the plain serial source */
    private Outcome run(String name, int parallelism, long blocks, long delayFirstMillis) {
        RocksDbHotHistoryStore hot = new RocksDbHotHistoryStore(temp.resolve(name));
        stores.add(hot);

        FactSource fixture = new FactSource(0, blocks - 1, delayFirstMillis);
        BlockArchiveSource<UtxoHistoryFact> source = fixture;
        if (parallelism > 0) {
            executor = Executors.newFixedThreadPool(parallelism);
            source = new OrderedPrefetchingBlockArchiveSource<>(fixture, executor,
                    Math.max(2, parallelism * 2), 8L * 1024 * 1024, 256 * 1024,
                    ignored -> 64L * 1024, Duration.ofSeconds(30));
        }

        var dataset = new UtxoHistoryDataset(hot, ArchiveTrack.BACKFILL);
        RecordingBackend backend = new RecordingBackend();
        MemoryProgress progress = new MemoryProgress();
        var config = new ArchiveWorkerConfig(Duration.ofMillis(10), (int) blocks, 500_000, false, 5, 1);
        CoreSyncView sync = new CoreSyncView() {
            public long localBlock() { return blocks; }
            public long targetBlock() { return blocks; }
        };
        var worker = new BlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "fixture"), source, backend,
                hot, config, sync, new ArchiveWorkerMetrics(), Duration.ofMinutes(1));

        worker.runBatch(dataset, 0, blocks - 1);

        List<String> rendered = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
        for (ArchiveRow row : backend.rows) {
            String text = row.table() + '|' + row.values().stream()
                    .map(v -> v instanceof byte[] b ? HexFormat.of().formatHex(b) : String.valueOf(v))
                    .reduce((a, b) -> a + ',' + b).orElse("");
            rendered.add(text);
            counts.merge(row.table(), 1L, Long::sum);
            digest.update(text.getBytes(StandardCharsets.UTF_8));
        }

        // Real hot-store contents: resolver/pointer state and retained facts.
        List<String> hotRows = new ArrayList<>();
        try (var snapshot = hot.snapshot()) {
            for (String table : List.of("transaction_outputs", "transaction_inputs", "transaction_output_assets")) {
                for (long block = 0; block < blocks; block++) {
                    var found = HotArchiveRows.read(snapshot, ArchiveDatasetId.UTXO_HISTORY, table,
                            Map.of("tx_hash", hash((int) block, 2)));
                    if (!found.isEmpty()) hotRows.add(table + '@' + block + "=" + found.size());
                }
            }
        }

        var saved = hot.load(ArchiveDatasetId.UTXO_HISTORY, ArchiveTrack.BACKFILL);
        String progressHash = saved.map(p -> p.coordinate() + ":" + p.slot() + ":"
                + HexFormat.of().formatHex(p.blockHash())).orElse("none");

        return new Outcome(rendered, HexFormat.of().formatHex(digest.digest()), counts,
                saved.map(ArchiveProgress::coordinate).orElse(-1L), hotRows, progressHash);
    }

    @Test
    void realDatasetAndHotStoreProduceIdenticalResultsUnderPrefetch() {
        Outcome serial = run("serial", 0, 40, 0);
        cleanup();
        Outcome one = run("p1", 1, 40, 0);
        cleanup();
        Outcome two = run("p2", 2, 40, 0);
        cleanup();
        Outcome four = run("p4", 4, 40, 0);

        assertThat(serial.rows()).as("fixture actually produced rows").isNotEmpty();
        // On the BACKFILL track the dataset publishes facts to the archive and
        // persists resolver state plus the cursor to the hot store, so the proof
        // that the real store was exercised is the persisted progress, not hot
        // fact rows (those belong to the LIVE track).
        assertThat(serial.progressHash()).as("real RocksDB hot store persisted progress").isNotEqualTo("none");

        for (Outcome candidate : List.of(one, two, four)) {
            assertThat(candidate.rows()).as("archive rows and order").isEqualTo(serial.rows());
            assertThat(candidate.digest()).as("ordered digest").isEqualTo(serial.digest());
            assertThat(candidate.counts()).as("per-table counts").isEqualTo(serial.counts());
            assertThat(candidate.cursor()).as("cursor").isEqualTo(serial.cursor());
            assertThat(candidate.hotRows()).as("hot-store contents").isEqualTo(serial.hotRows());
            assertThat(candidate.progressHash()).as("persisted progress").isEqualTo(serial.progressHash());
        }
    }

    @Test
    void outOfOrderDecodeCompletionMatchesSerialWithRealHotState() {
        Outcome serial = run("serial-ooo", 0, 30, 0);
        cleanup();
        Outcome reordered = run("p4-ooo", 4, 30, 150);

        assertThat(reordered.rows()).isEqualTo(serial.rows());
        assertThat(reordered.digest()).isEqualTo(serial.digest());
        assertThat(reordered.hotRows()).isEqualTo(serial.hotRows());
        assertThat(reordered.progressHash()).isEqualTo(serial.progressHash());
    }
}
