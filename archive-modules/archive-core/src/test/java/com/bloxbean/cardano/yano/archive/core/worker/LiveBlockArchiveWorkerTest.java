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

import static org.assertj.core.api.Assertions.assertThat;

class LiveBlockArchiveWorkerTest {
    @TempDir Path temp;

    @Test
    void liveRowsArePinnedAndExactUndoRemovesOrphanedTransaction() {
        byte[] hash = {1};
        BlockSourceContext<ArchiveBlockFacts> block = new BlockSourceContext<>(10, 20, 0, Instant.EPOCH,
                hash, new byte[]{0}, new ArchiveBlockFacts(List.of(new TransactionFact(new byte[]{9}, 0, false, 3)), List.of()));
        BlockArchiveSource<ArchiveBlockFacts> source = new BlockArchiveSource<>() {
            public Optional<BlockSourceContext<ArchiveBlockFacts>> readCanonical(long number) {
                return number == 10 ? Optional.of(block) : Optional.empty();
            }
            public ArchiveSourceLease acquire(long start, long end, Instant expiry) {
                return new ArchiveSourceLease() {
                    public UUID leaseId() { return UUID.randomUUID(); }
                    public Instant expiresAt() { return expiry; }
                    public ArchiveSourceLease renew(Instant value) { return this; }
                    public void close() { }
                };
            }
            public long earliestRetainedBody() { return 10; }
        };
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            var worker = new LiveBlockArchiveWorker<>(new ArchiveNetworkIdentity(1, "g"), source, hot,
                    new ArchiveWorkerConfig(Duration.ofSeconds(1), 10, 100, 1), new ArchiveWorkerMetrics());
            worker.runBatch(StandardBlockDatasets.transactions(), 10, 10);
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
}
