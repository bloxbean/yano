package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Observable contract shared by every ADR-036 hot-store implementation. */
public abstract class AbstractHotHistoryStoreConformanceTest {
    @TempDir
    protected Path temp;

    protected abstract HotHistoryStore open(Path path);

    @Test
    void batchApplyPublishesRowsCheckpointAndProgressAtomically() {
        try (HotHistoryStore store = open(temp.resolve("atomic"))) {
            byte[] key = bytes("row/one");
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryMutation(key, bytes("value"))), progress(1, 10, 1));

            assertThat(store.get(ArchiveDatasetId.TRANSACTION, key)).contains(bytes("value"));
            assertThat(store.checkpoint(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 1)).isPresent();
            assertThat(store.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE))
                    .get().extracting(ArchiveProgress::coordinate).isEqualTo(1L);
        }
    }

    @Test
    void readSnapshotPinsThePreCleanupView() {
        try (HotHistoryStore store = open(temp.resolve("snapshot"))) {
            byte[] key = bytes("row/pinned");
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryMutation(key, bytes("before"))), progress(1, 10, 1));

            try (HotHistorySnapshot snapshot = store.snapshot()) {
                store.deleteData(ArchiveDatasetId.TRANSACTION, List.of(key));
                assertThat(store.get(ArchiveDatasetId.TRANSACTION, key)).isEmpty();
                assertThat(snapshot.scan(ArchiveDatasetId.TRANSACTION, key))
                        .singleElement()
                        .extracting(HotHistorySnapshot.Entry::value)
                        .isEqualTo(bytes("before"));
            }
        }
    }

    @Test
    void rollbackRestoresAnOlderValueAndCursor() {
        try (HotHistoryStore store = open(temp.resolve("rollback"))) {
            byte[] key = bytes("resolver/output");
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryMutation(key, bytes("created"))), progress(1, 10, 1));
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(2, 20, 2, 1),
                    List.of(new HotHistoryMutation(key, null)), progress(2, 20, 2));

            store.rollbackTo(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 1);

            assertThat(store.get(ArchiveDatasetId.TRANSACTION, key)).contains(bytes("created"));
            assertThat(store.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE))
                    .get().extracting(ArchiveProgress::coordinate).isEqualTo(1L);
        }
    }

    @Test
    void bodyRequirementsAndLeasesShareOneDurableBoundary() {
        try (HotHistoryStore store = open(temp.resolve("retention"))) {
            store.requireBlockBodiesFrom(ArchiveDatasetId.TRANSACTION, 50);
            try (var lease = store.acquireBlockBodyLease(40, 45, Instant.now().plusSeconds(60))) {
                assertThat(store.oldestRequiredBlockNumber()).hasValue(40);
            }
            assertThat(store.oldestRequiredBlockNumber()).hasValue(50);
            store.releaseBlockBodyRequirement(ArchiveDatasetId.TRANSACTION);
            assertThat(store.oldestRequiredBlockNumber()).isEmpty();
        }
    }

    private ArchiveProgress progress(long block, long slot, int hash) {
        return new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE,
                block, slot, new byte[] {(byte) hash}, 0);
    }

    private HotBlockCheckpoint checkpoint(long block, long slot, int hash, int parent) {
        return new HotBlockCheckpoint(block, slot, new byte[] {(byte) hash}, new byte[] {(byte) parent});
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
