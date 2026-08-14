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
    void exactUndoRestoresReplacedAndDeletedValuesWithoutSlotScan() {
        byte[] key = bytes("subject/one");
        try (var store = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryMutation(key, bytes("one"))), progress(1, 10, 1));
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(2, 20, 2, 1),
                    List.of(new HotHistoryMutation(key, bytes("two"))), progress(2, 20, 2));
            try (var pinned = store.snapshot()) {
                store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(3, 30, 3, 2),
                        List.of(new HotHistoryMutation(key, null)), progress(3, 30, 3));
                assertThat(store.get(ArchiveDatasetId.TRANSACTION, key)).isEmpty();
                assertThat(pinned.get(physicalKey(key))).contains(bytes("two"));
            }
            store.rollbackTo(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 1);
            assertThat(store.get(ArchiveDatasetId.TRANSACTION, key)).contains(bytes("one"));
            assertThat(store.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE).orElseThrow().coordinate())
                    .isEqualTo(1);
        }
    }

    @Test
    void sameBlockCreateThenReplaceRollsBackWithoutPhantomValue() {
        byte[] key = bytes("same-block");
        try (var store = new RocksDbHotHistoryStore(temp.resolve("same-block-undo"))) {
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(0, 0, 0, -1),
                    List.of(), progress(0, 0, 0));
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryMutation(key, bytes("created")),
                            new HotHistoryMutation(key, bytes("replaced"))), progress(1, 10, 1));
            assertThat(store.get(ArchiveDatasetId.TRANSACTION, key)).contains(bytes("replaced"));

            store.rollbackTo(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 0);

            assertThat(store.get(ArchiveDatasetId.TRANSACTION, key)).isEmpty();
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

    @Test
    void sharedAddressDimensionKeepsEarliestTrackObservationAndUndoRestoresLaterOne() {
        byte[] addressKey = new byte[32];
        Arrays.fill(addressKey, (byte) 7);
        HotHistoryMutation later = address(addressKey, 10, 100, 1);
        HotHistoryMutation earlier = address(addressKey, 2, 20, 0);
        try (var store = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            store.applyBlock(ArchiveDatasetId.UTXO_HISTORY, checkpoint(10, 100, 10, 9), List.of(later),
                    new ArchiveProgress(ArchiveDatasetId.UTXO_HISTORY, ArchiveTrack.LIVE,
                            10, 100, new byte[] {10}, 0));
            store.applyBlock(ArchiveDatasetId.UTXO_HISTORY, checkpoint(2, 20, 2, 1), List.of(earlier),
                    new ArchiveProgress(ArchiveDatasetId.UTXO_HISTORY, ArchiveTrack.BACKFILL,
                            2, 20, new byte[] {2}, 0));
            assertThat(firstSeen(store, addressKey)).isEqualTo(2);

            store.resetTrackFrom(ArchiveDatasetId.UTXO_HISTORY, ArchiveTrack.BACKFILL, 2);
            assertThat(firstSeen(store, addressKey)).isEqualTo(10);
        }
    }

    private long firstSeen(RocksDbHotHistoryStore store, byte[] addressKey) {
        try (var snapshot = store.snapshot()) {
            return ((Number) HotArchiveRows.read(snapshot, ArchiveDatasetId.UTXO_HISTORY, "addresses",
                    java.util.Map.of("address_key", addressKey)).getFirst()
                    .value("first_seen_block_number")).longValue();
        }
    }

    private HotHistoryMutation address(byte[] key, long block, long slot, long epoch) {
        return HotArchiveRows.put(ArchiveDatasetId.UTXO_HISTORY,
                new ArchiveRow("addresses", Arrays.asList(key, new byte[] {9}, "addr_test", 0,
                        "enterprise", "key", new byte[] {3}, "none", null, null,
                        null, null, null, block, slot, epoch)));
    }

    private ArchiveProgress progress(long block, long slot, int hash) {
        return new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE,
                block, slot, new byte[] {(byte) hash}, 0);
    }

    private HotBlockCheckpoint checkpoint(long block, long slot, int hash, int parent) {
        return new HotBlockCheckpoint(block, slot, new byte[] {(byte) hash}, new byte[] {(byte) parent});
    }

    private byte[] physicalKey(byte[] logical) {
        return bytes("d/TRANSACTION/" + new String(logical, StandardCharsets.UTF_8));
    }

    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
