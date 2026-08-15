package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocksDbHotHistoryStoreTest {
    @TempDir Path temp;

    @Test
    void exactUndoRestoresConsumedResolverOutputWithoutSlotScan() {
        try (var store = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            var outpoint = new com.bloxbean.cardano.yano.archive.core.address.Outpoint(hash(1), 0);
            var output = new com.bloxbean.cardano.yano.archive.core.address.ResolvedOutput(hash(2), "addr",
                    Arrays.copyOf(hash(3), 28), "key", Arrays.copyOf(hash(4), 28));
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryOperation.OutputCreated("live", outpoint, output)),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 10, 1));
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(2, 20, 2, 1),
                    List.of(new HotHistoryOperation.OutputConsumed("live", outpoint, hash(5), "ordinary")),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 2, 20, 2));
            store.rollbackTo(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE, 1);
            assertThat(store.resolveOutput("live", outpoint)).isPresent();
            assertThat(store.load(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE).orElseThrow().coordinate())
                    .isEqualTo(1);
        }
    }

    @Test
    void sameBlockCreateThenConsumeRollsBackWithoutPhantomValue() {
        try (var store = new RocksDbHotHistoryStore(temp.resolve("same-block-undo"))) {
            var outpoint = new com.bloxbean.cardano.yano.archive.core.address.Outpoint(hash(6), 0);
            var output = new com.bloxbean.cardano.yano.archive.core.address.ResolvedOutput(hash(7), "addr",
                    Arrays.copyOf(hash(8), 28), "key", Arrays.copyOf(hash(9), 28));
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(0, 0, 0, -1),
                    List.of(), progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 0, 0, 0));
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryOperation.OutputCreated("live", outpoint, output),
                            new HotHistoryOperation.OutputConsumed("live", outpoint, hash(10), "ordinary")),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 10, 1));
            store.rollbackTo(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE, 0);
            assertThat(store.resolveOutput("live", outpoint)).isEmpty();
        }
    }

    @Test
    void missingUndoFailsClosedAndPrunedUndoCannotSupportDeepRollback() {
        try (var store = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0), List.of(), progress(1, 10, 1));
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(2, 20, 2, 1), List.of(), progress(2, 20, 2));
            store.pruneUndoThrough(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 1);
            assertThat(store.checkpoint(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 1)).isEmpty();
            assertThat(store.checkpoint(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 2)).isPresent();
            assertThatThrownBy(() -> store.rollbackTo(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing exact undo");
        }
    }

    @Test
    void blockBodyLeaseIsDurableAcrossRestartAndReleasedExplicitly() {
        Path path = temp.resolve("hot");
        try (var store = new RocksDbHotHistoryStore(path)) {
            store.acquireBlockBodyLease(42, 50, Instant.now().plusSeconds(60));
            assertThat(store.oldestRequiredBlockNumber()).hasValue(42);
        }
        try (var reopened = new RocksDbHotHistoryStore(path)) {
            assertThat(reopened.oldestRequiredBlockNumber()).hasValue(42);
            try (var newer = reopened.acquireBlockBodyLease(60, 70, Instant.now().plusSeconds(60))) {
                assertThat(reopened.oldestRequiredBlockNumber()).hasValue(42);
            }
        }
    }

    @Test
    void durableConsumerWatermarkSurvivesPollGapsAndAdvancesWithBackfillProgress() {
        Path path = temp.resolve("hot");
        try (var store = new RocksDbHotHistoryStore(path)) {
            store.requireBlockBodiesFrom(ArchiveDatasetId.TRANSACTION, 20);
            assertThat(store.oldestRequiredBlockNumber()).hasValue(20);
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(20, 200, 20, 19), List.of(),
                    new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.BACKFILL,
                            20, 200, new byte[] {20}, 0));
            assertThat(store.oldestRequiredBlockNumber()).hasValue(21);
        }
        try (var reopened = new RocksDbHotHistoryStore(path)) {
            assertThat(reopened.oldestRequiredBlockNumber()).hasValue(21);
            reopened.releaseBlockBodyRequirement(ArchiveDatasetId.TRANSACTION);
            assertThat(reopened.oldestRequiredBlockNumber()).isEmpty();
        }
    }

    @Test
    void liveAndBackfillUndoAtSameBlockRemainIndependent() {
        try (var store = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            var point = checkpoint(0, 0, 5, 4);
            store.applyBlock(ArchiveDatasetId.TRANSACTION, point, List.of(),
                    new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.BACKFILL,
                            0, 0, new byte[] {5}, 1));
            store.applyBlock(ArchiveDatasetId.TRANSACTION, point, List.of(),
                    new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE,
                            0, 0, new byte[] {5}, 0));

            store.pruneUndoThrough(ArchiveDatasetId.TRANSACTION, ArchiveTrack.BACKFILL, 0);
            store.rollbackTo(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, -1);
            assertThat(store.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE)).isEmpty();
            assertThat(store.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.BACKFILL)).isPresent();
        }
    }

    private ArchiveProgress progress(long block, long slot, int hash) {
        return new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE,
                block, slot, new byte[] {(byte) hash}, 0);
    }
    private ArchiveProgress progress(ArchiveDatasetId dataset, long block, long slot, int hash) {
        return new ArchiveProgress(dataset, ArchiveTrack.LIVE, block, slot, new byte[] {(byte) hash}, 0);
    }

    private HotBlockCheckpoint checkpoint(long block, long slot, int hash, int parent) {
        return new HotBlockCheckpoint(block, slot, new byte[] {(byte) hash}, new byte[] {(byte) parent});
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private byte[] hash(int marker) { byte[] value = new byte[32]; Arrays.fill(value, (byte) marker); return value; }
}
