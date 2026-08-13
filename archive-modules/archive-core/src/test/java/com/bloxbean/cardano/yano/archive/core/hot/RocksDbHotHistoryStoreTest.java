package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
    void missingUndoFailsClosedAndPrunedUndoCannotSupportDeepRollback() {
        try (var store = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0), List.of(), progress(1, 10, 1));
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(2, 20, 2, 1), List.of(), progress(2, 20, 2));
            store.pruneUndoThrough(ArchiveDatasetId.TRANSACTION, 1);
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
