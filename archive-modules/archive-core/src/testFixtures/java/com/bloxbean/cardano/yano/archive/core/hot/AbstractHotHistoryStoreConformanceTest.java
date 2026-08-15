package com.bloxbean.cardano.yano.archive.core.hot;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.address.*;
import com.bloxbean.cardano.yano.archive.core.worker.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Observable semantic contract shared by every ADR-036 hot-store implementation. */
public abstract class AbstractHotHistoryStoreConformanceTest {
    @TempDir protected Path temp;
    protected abstract HotHistoryStore open(Path path);

    @Test void batchApplyPublishesFactCheckpointAndProgressAtomically() {
        try (HotHistoryStore store = open(temp.resolve("atomic"))) {
            ArchiveRow row = transaction(1, 10, 1);
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryOperation.Fact(row)), progress(ArchiveDatasetId.TRANSACTION, 1, 10, 1));
            assertThat(store.findFact(ArchiveDatasetId.TRANSACTION, HotArchiveRows.key(ArchiveDatasetId.TRANSACTION, row)))
                    .isPresent();
            assertThat(store.checkpoint(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 1)).isPresent();
            assertThat(store.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE))
                    .get().extracting(ArchiveProgress::coordinate).isEqualTo(1L);
        }
    }

    @Test void readSnapshotPinsThePreCleanupView() {
        try (HotHistoryStore store = open(temp.resolve("snapshot"))) {
            ArchiveRow row = transaction(1, 10, 2);
            byte[] key = HotArchiveRows.key(ArchiveDatasetId.TRANSACTION, row);
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 2, 0),
                    List.of(new HotHistoryOperation.Fact(row)), progress(ArchiveDatasetId.TRANSACTION, 1, 10, 2));
            try (HotHistorySnapshot snapshot = store.snapshot()) {
                assertThat(snapshot.scanTable(ArchiveDatasetId.TRANSACTION, "chain_transaction")).hasSize(1);
                store.deleteFacts(ArchiveDatasetId.TRANSACTION, List.of(key));
                assertThat(store.findFact(ArchiveDatasetId.TRANSACTION, key)).isEmpty();
                assertThat(snapshot.scanTable(ArchiveDatasetId.TRANSACTION, "chain_transaction")).hasSize(1);
            }
        }
    }

    @Test void consumedResolverOutputIsRestoredBySuffixRollback() {
        try (HotHistoryStore store = open(temp.resolve("rollback"))) {
            Outpoint outpoint = new Outpoint(hash(9), 0);
            ResolvedOutput output = output(7);
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryOperation.OutputCreated("live", outpoint, output)),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 10, 1));
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(2, 20, 2, 1),
                    List.of(new HotHistoryOperation.OutputConsumed("live", outpoint, hash(10), "ordinary")),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 2, 20, 2));
            assertThat(store.resolveOutput("live", outpoint)).isEmpty();
            store.rollbackTo(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE, 1);
            ResolvedOutput restored = store.resolveOutput("live", outpoint).orElseThrow();
            assertThat(restored.address()).isEqualTo(output.address());
            assertThat(restored.addressKey()).containsExactly(output.addressKey());
            assertThat(restored.paymentCredential()).containsExactly(output.paymentCredential());
            assertThat(restored.stakeCredential()).containsExactly(output.stakeCredential());
        }
    }

    @Test void sameBlockCreateAndConsumeRollsBackWithoutPhantomOutpoint() {
        try (HotHistoryStore store = open(temp.resolve("same-block"))) {
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(0, 0, 0, -1), List.of(),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 0, 0, 0));
            Outpoint outpoint = new Outpoint(hash(11), 1);
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(1, 10, 1, 0), List.of(
                    new HotHistoryOperation.OutputCreated("live", outpoint, output(1)),
                    new HotHistoryOperation.OutputConsumed("live", outpoint, hash(12), "ordinary")),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 10, 1));
            store.rollbackTo(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE, 0);
            assertThat(store.resolveOutput("live", outpoint)).isEmpty();
        }
    }

    @Test void identicalCreateAndConsumeReplayIsIdempotent() {
        try (HotHistoryStore store = open(temp.resolve("resolver-replay"))) {
            Outpoint outpoint = new Outpoint(hash(13), 1);
            HotBlockCheckpoint block = checkpoint(1, 10, 1, 0);
            List<HotHistoryOperation> operations = List.of(
                    new HotHistoryOperation.OutputCreated("live", outpoint, output(13)),
                    new HotHistoryOperation.OutputConsumed("live", outpoint, hash(14), "ordinary"));
            ArchiveProgress progress = progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 10, 1);

            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, block, operations, progress);
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, block, operations, progress);

            assertThat(store.resolveOutput("live", outpoint)).isEmpty();
            assertThat(store.checkpoint(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE, 1)).isPresent();
        }
    }

    @Test void failedBlockOperationDoesNotPublishFactsCheckpointOrProgress() {
        try (HotHistoryStore store = open(temp.resolve("atomic-failure"))) {
            Outpoint missing = new Outpoint(hash(32), 0);
            assertThatThrownBy(() -> store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION,
                    checkpoint(1, 10, 1, 0), List.of(new HotHistoryOperation.OutputConsumed(
                            "live", missing, hash(33), "ordinary")),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 10, 1))).isInstanceOf(RuntimeException.class);
            assertThat(store.checkpoint(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE, 1)).isEmpty();
            assertThat(store.load(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE)).isEmpty();
        }
    }

    @Test void pointerDeregistrationAndRollbackAreAppendOnlyAndExact() {
        try (HotHistoryStore store = open(temp.resolve("pointer"))) {
            byte[] credential = Arrays.copyOf(hash(22), 28);
            var pointer = new SequentialPointerResolver.PointerCoordinate(100, 0, 0);
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(1, 100, 1, 0),
                    List.of(new HotHistoryOperation.PointerRegistered("live", 100, 0, 0, "key", credential)),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 100, 1));
            assertThat(store.resolvePointer(ArchiveDatasetId.ADDRESS_TRANSACTION, "live", pointer)).isPresent();
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(2, 200, 2, 1),
                    List.of(new HotHistoryOperation.PointerDeregistered("live", 200, 0, 0, "key", credential)),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 2, 200, 2));
            assertThat(store.resolvePointer(ArchiveDatasetId.ADDRESS_TRANSACTION, "live", pointer)).isEmpty();
            store.rollbackTo(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.LIVE, 1);
            assertThat(store.resolvePointer(ArchiveDatasetId.ADDRESS_TRANSACTION, "live", pointer)).isPresent();
        }
    }

    @Test void sameBlockPointerRegistrationThenDeregistrationIsNotLeftActive() {
        try (HotHistoryStore store = open(temp.resolve("same-block-pointer"))) {
            byte[] credential = Arrays.copyOf(hash(23), 28);
            var pointer = new SequentialPointerResolver.PointerCoordinate(100, 0, 0);
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(1, 100, 1, 0), List.of(
                    new HotHistoryOperation.PointerRegistered("live", 100, 0, 0, "key", credential),
                    new HotHistoryOperation.PointerDeregistered("live", 100, 0, 1, "key", credential)),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 100, 1));
            assertThat(store.resolvePointer(ArchiveDatasetId.ADDRESS_TRANSACTION, "live", pointer)).isEmpty();
        }
    }

    @Test void bodyRequirementsAndLeasesShareOneDurableBoundary() {
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

    @Test void committedFactsAndResolverStateSurviveAReopen() {
        Path path = temp.resolve("reopen");
        ArchiveRow row = transaction(1, 10, 41);
        byte[] rowKey = HotArchiveRows.key(ArchiveDatasetId.TRANSACTION, row);
        Outpoint outpoint = new Outpoint(hash(42), 0);
        byte[] credential = Arrays.copyOf(hash(43), 28);
        var pointer = new SequentialPointerResolver.PointerCoordinate(10, 0, 0);

        try (HotHistoryStore store = open(path)) {
            store.applyBlock(ArchiveDatasetId.TRANSACTION, checkpoint(1, 10, 1, 0),
                    List.of(new HotHistoryOperation.Fact(row)),
                    progress(ArchiveDatasetId.TRANSACTION, 1, 10, 1));
            store.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION, checkpoint(1, 10, 1, 0), List.of(
                    new HotHistoryOperation.OutputCreated("live", outpoint, output(42)),
                    new HotHistoryOperation.PointerRegistered("live", 10, 0, 0, "key", credential)),
                    progress(ArchiveDatasetId.ADDRESS_TRANSACTION, 1, 10, 1));
        }

        try (HotHistoryStore reopened = open(path)) {
            assertThat(reopened.findFact(ArchiveDatasetId.TRANSACTION, rowKey)).isPresent();
            assertThat(reopened.load(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE))
                    .get().extracting(ArchiveProgress::coordinate).isEqualTo(1L);
            assertThat(reopened.resolveOutput("live", outpoint)).isPresent();
            assertThat(reopened.resolvePointer(
                    ArchiveDatasetId.ADDRESS_TRANSACTION, "live", pointer)).isPresent();
        }
    }

    private ArchiveRow transaction(long block, long slot, int marker) {
        return new ArchiveRow("chain_transaction", Arrays.asList(hash(marker), hash(marker + 1), block, slot,
                0L, 1_700_000_000L + block, 0, true, 10L, new UUID(0, marker)));
    }
    private ArchiveProgress progress(ArchiveDatasetId dataset, long block, long slot, int hash) {
        return new ArchiveProgress(dataset, ArchiveTrack.LIVE, block, slot, new byte[]{(byte) hash}, 0);
    }
    private HotBlockCheckpoint checkpoint(long block, long slot, int hash, int parent) {
        return new HotBlockCheckpoint(block, slot, new byte[]{(byte) hash}, new byte[]{(byte) parent});
    }
    private ResolvedOutput output(int marker) {
        return new ResolvedOutput(hash(marker), "addr_test1_" + marker, Arrays.copyOf(hash(marker + 1), 28),
                "key", Arrays.copyOf(hash(marker + 2), 28));
    }
    private byte[] hash(int marker) { byte[] value = new byte[32]; Arrays.fill(value, (byte) marker); return value; }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
