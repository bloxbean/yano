package com.bloxbean.cardano.yano.archive.core.worker;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveWorkerConfig;
import com.bloxbean.cardano.yano.archive.core.dataset.*;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.ArchiveSourceLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveBlockArchiveWorkerTest {
    @TempDir Path temp;

    private static final byte[] BLOCK_HASH = {1};

    private static BlockSourceContext<ArchiveBlockFacts> block() {
        return new BlockSourceContext<>(10, 20, 0, Instant.EPOCH, BLOCK_HASH, new byte[]{0},
                new ArchiveBlockFacts(List.of(new TransactionFact(new byte[]{9}, 0, false, 3)), List.of()));
    }

    private static ArchiveSourceLease lease(Instant expiry) {
        return new ArchiveSourceLease() {
            public UUID leaseId() { return UUID.randomUUID(); }
            public Instant expiresAt() { return expiry; }
            public ArchiveSourceLease renew(Instant value) { return this; }
            public void close() { }
        };
    }

    private static BlockArchiveSource<ArchiveBlockFacts> stableSource(BlockSourceContext<ArchiveBlockFacts> block) {
        return new BlockArchiveSource<>() {
            public Optional<BlockSourceContext<ArchiveBlockFacts>> readCanonical(long number) {
                return number == 10 ? Optional.of(block) : Optional.empty();
            }
            public ArchiveSourceLease acquire(long start, long end, Instant expiry) { return lease(expiry); }
            public long earliestRetainedBody() { return 10; }
        };
    }

    /**
     * Reports the canonical batch during the read, then a different hash once the
     * batch has been read. This is the fork-switch window the near-tip recheck
     * closes: only a check performed after derivation, immediately before the hot
     * commit, can observe it.
     */
    private static BlockArchiveSource<ArchiveBlockFacts> forkAfterReadSource(
            BlockSourceContext<ArchiveBlockFacts> block, AtomicBoolean read) {
        return new BlockArchiveSource<>() {
            public Optional<BlockSourceContext<ArchiveBlockFacts>> readCanonical(long number) {
                if (number != 10) return Optional.empty();
                read.set(true);
                return Optional.of(block);
            }
            @Override
            public Optional<CanonicalBlockReference> canonicalReference(long number) {
                if (number != 10) return Optional.empty();
                byte[] hash = read.get() ? new byte[]{99} : BLOCK_HASH;
                return Optional.of(new CanonicalBlockReference(10, 20, hash));
            }
            public ArchiveSourceLease acquire(long start, long end, Instant expiry) { return lease(expiry); }
            public long earliestRetainedBody() { return 10; }
        };
    }

    private LiveBlockArchiveWorker<ArchiveBlockFacts> worker(BlockArchiveSource<ArchiveBlockFacts> source,
                                                             HotHistoryStore hot) {
        return new LiveBlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "g"), source, hot,
                new ArchiveWorkerConfig(Duration.ofSeconds(1), 10, 100, 1), new ArchiveWorkerMetrics());
    }

    @Test
    void liveRowsArePinnedAndExactUndoRemovesOrphanedTransaction() {
        var block = block();
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            worker(stableSource(block), hot).runBatch(StandardBlockDatasets.transactions(), 10, 10);
            try (var pinned = hot.snapshot()) {
                assertThat(HotArchiveRows.read(pinned, ArchiveDatasetId.TRANSACTION, "chain_transaction",
                        Map.of("tx_hash", new byte[]{9}))).hasSize(1);
                hot.resetTrackFrom(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 10);
                assertThat(HotArchiveRows.read(pinned, ArchiveDatasetId.TRANSACTION, "chain_transaction",
                        Map.of("tx_hash", new byte[]{9}))).hasSize(1);
            }
            try (var current = hot.snapshot()) {
                assertThat(HotArchiveRows.read(current, ArchiveDatasetId.TRANSACTION, "chain_transaction",
                        Map.of("tx_hash", new byte[]{9}))).isEmpty();
            }
        }
    }

    /**
     * Phase 2b, stateless commit path: the batch must abort before
     * {@code hot.applyBlocks} rather than making hot facts for an orphaned range
     * durable.
     */
    @Test
    void staleAnchorAbortsStatelessLiveBatchBeforeHotCommit() {
        var block = block();
        AtomicBoolean read = new AtomicBoolean();
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            var worker = worker(forkAfterReadSource(block, read), hot);

            assertThatThrownBy(() -> worker.runBatch(StandardBlockDatasets.transactions(), 10, 10))
                    .isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("canonical live anchor changed at block 10");

            assertThat(hot.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE)).isEmpty();
            try (var current = hot.snapshot()) {
                assertThat(HotArchiveRows.read(current, ArchiveDatasetId.TRANSACTION, "chain_transaction",
                        Map.of("tx_hash", new byte[]{9}))).isEmpty();
            }
        }
    }

    /**
     * Phase 2b, stateful commit path: the recheck sits before the
     * stateful/stateless branch, so the stateful dataset must abort its private
     * derived state rather than commit it.
     */
    @Test
    void staleAnchorAbortsStatefulLiveBatchAndInvokesAbortBatch() {
        var block = block();
        AtomicBoolean read = new AtomicBoolean();
        var dataset = new RecordingLiveStatefulDataset();
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            var worker = worker(forkAfterReadSource(block, read), hot);

            assertThatThrownBy(() -> worker.runBatch(dataset, 10, 10))
                    .isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("canonical live anchor changed at block 10");

            assertThat(dataset.beganBatch).as("batch was started").isTrue();
            assertThat(dataset.abortedBatch).as("abortBatch() invoked after the failed recheck").isTrue();
            assertThat(dataset.committedLiveBatch).as("private derived state must not commit").isFalse();
            assertThat(hot.load(dataset.dataset(), ArchiveTrack.LIVE)).isEmpty();
        }
    }

    /** Minimal stateful live dataset that records its batch lifecycle callbacks. */
    private static final class RecordingLiveStatefulDataset
            implements LiveStatefulBlockArchiveDataset<ArchiveBlockFacts> {
        boolean beganBatch;
        boolean abortedBatch;
        boolean committedLiveBatch;

        public ArchiveDatasetId dataset() { return ArchiveDatasetId.ADDRESS_TRANSACTION; }

        public int projectionVersion() { return 1; }

        public void derive(ArchiveJob job, BlockSourceContext<ArchiveBlockFacts> source,
                           Consumer<ArchiveRow> sink) {
            sink.accept(new ArchiveRow("address_transactions", List.of(new byte[]{7})));
        }

        public void beginBatch(ArchiveJob job, List<BlockSourceContext<ArchiveBlockFacts>> blocks) {
            beganBatch = true;
        }

        public void commitBatch(ArchiveReceipt receipt) { }

        public void commitCoveredBatch(long backendGeneration) { }

        public void abortBatch() { abortedBatch = true; }

        public void commitLiveBatch(List<List<HotHistoryOperation>> rowOperations) {
            committedLiveBatch = true;
        }
    }
}
