package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.*;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.worker.*;
import com.bloxbean.cardano.yano.archive.sqlite.SqliteArchiveBackendProvider;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

class HistoryArchivePromotionTest {
    @TempDir Path temp;

    @Test
    void yieldsColdFrontierToCaughtUpBackfillInsteadOfExtendingItInPromotionSizedSteps() throws Exception {
        byte[] txHash = java.util.HexFormat.of().parseHex("01".repeat(32));
        byte[] liveBlockHash = java.util.HexFormat.of().parseHex("02".repeat(32));
        byte[] previousBlockHash = java.util.HexFormat.of().parseHex("03".repeat(32));
        ArchiveIdentity identity = new ArchiveIdentity(UUID.randomUUID(), "sqlite", 1, 1, "fixture");
        ArchiveBackend backend = new SqliteArchiveBackendProvider().open(identity, temp,
                Map.of("database.path", temp.resolve("frontier.sqlite").toString()));
        RocksDbHotHistoryStore hot = new RocksDbHotHistoryStore(temp.resolve("frontier-hot"));
        ArchiveRow row = new ArchiveRow("chain_transaction", List.of(txHash, liveBlockHash, 10L, 100L, 0L,
                1_700_000_000L, 0, true, 5L, UUID.randomUUID()));
        hot.applyBlock(ArchiveDatasetId.TRANSACTION,
                new HotBlockCheckpoint(10, 100, liveBlockHash, previousBlockHash),
                List.of(HotArchiveRows.put(ArchiveDatasetId.TRANSACTION, row)),
                new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE,
                        10, 100, liveBlockHash, 0));
        hot.applyBlock(ArchiveDatasetId.TRANSACTION,
                new HotBlockCheckpoint(9, 99, previousBlockHash, new byte[32]), List.of(),
                new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.BACKFILL,
                        9, 99, previousBlockHash, 0));

        var activations = new ActivationStore(temp.resolve("frontier-activation.properties"));
        activations.putIfAbsent(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 10);
        var configuration = new ArchiveConfiguration(true, temp, ArchiveEngine.SQLITE,
                ArchiveStartMode.TIP, true, ArchiveWorkerConfig.defaults(),
                ArchiveSafetyWindows.resolve(1, 1L, 1L),
                Map.of(ArchiveDatasetId.TRANSACTION,
                        new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0)));
        var service = new HistoryArchiveService(mock(Config.class));
        set(service, "backend", backend);
        set(service, "controlStore", hot);
        set(service, "archiveConfig", configuration);
        set(service, "activations", activations);

        service.promoteLiveRows(ArchiveDatasetId.TRANSACTION, 10);

        assertThat(backend.coverage(ArchiveDatasetId.TRANSACTION).covers(10)).isFalse();
        try (var current = hot.snapshot()) {
            assertThat(HotArchiveRows.read(current, ArchiveDatasetId.TRANSACTION,
                    "chain_transaction", Map.of())).hasSize(1);
        }

        backend.close();
        hot.close();
    }

    @Test
    void commitsPinnedLiveRowsBeforeDeletingThemAndLeavesOldReadersGapFree() throws Exception {
        byte[] txHash = java.util.HexFormat.of().parseHex("11".repeat(32));
        byte[] blockHash = java.util.HexFormat.of().parseHex("22".repeat(32));
        ArchiveIdentity identity = new ArchiveIdentity(UUID.randomUUID(), "sqlite", 1, 1, "fixture");
        ArchiveBackend backend = new SqliteArchiveBackendProvider().open(identity, temp,
                Map.of("database.path", temp.resolve("archive.sqlite").toString()));
        RocksDbHotHistoryStore hot = new RocksDbHotHistoryStore(temp.resolve("hot"));
        var row = new ArchiveRow("chain_transaction", List.of(txHash, blockHash, 10L, 100L, 0L,
                1_700_000_000L, 0, true, 5L, UUID.randomUUID()));
        hot.applyBlock(ArchiveDatasetId.TRANSACTION,
                new HotBlockCheckpoint(10, 100, blockHash, new byte[32]),
                List.of(HotArchiveRows.put(ArchiveDatasetId.TRANSACTION, row)),
                new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE,
                        10, 100, blockHash, 0));
        var oldSnapshot = hot.snapshot();

        ChainQuery chain = mock(ChainQuery.class);
        when(chain.getCanonicalBlockReference(10)).thenReturn(Optional.of(
                new CanonicalBlockReference(10, 100, blockHash)));
        var activations = new ActivationStore(temp.resolve("activation.properties"));
        activations.putIfAbsent(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE, 10);
        var datasets = Map.of(ArchiveDatasetId.TRANSACTION,
                new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0));
        var configuration = new ArchiveConfiguration(true, temp, ArchiveEngine.SQLITE,
                ArchiveStartMode.TIP, true, ArchiveWorkerConfig.defaults(),
                ArchiveSafetyWindows.resolve(1, 1L, 1L), datasets);
        var service = new HistoryArchiveService(mock(Config.class));
        set(service, "backend", backend);
        set(service, "controlStore", hot);
        set(service, "chain", chain);
        set(service, "archiveConfig", configuration);
        set(service, "activations", activations);

        service.promoteLiveRows(ArchiveDatasetId.TRANSACTION, 10);

        assertThat(backend.coverage(ArchiveDatasetId.TRANSACTION).covers(10)).isTrue();
        try (var read = backend.openReadSession()) {
            assertThat(backend.findTransaction(read, txHash)).isPresent();
        }
        try (var current = hot.snapshot()) {
            assertThat(HotArchiveRows.read(current, ArchiveDatasetId.TRANSACTION,
                    "chain_transaction", Map.of())).isEmpty();
        }
        assertThat(HotArchiveRows.read(oldSnapshot, ArchiveDatasetId.TRANSACTION,
                "chain_transaction", Map.of())).hasSize(1);

        oldSnapshot.close();
        backend.close();
        hot.close();
    }

    @Test
    void promotesTransactionScopedPayloadRowsByBlockRange() throws Exception {
        byte[] txHash = java.util.HexFormat.of().parseHex("31".repeat(32));
        byte[] blockHash = java.util.HexFormat.of().parseHex("32".repeat(32));
        byte[] addressKey = java.util.HexFormat.of().parseHex("33".repeat(32));
        byte[] datumHash = java.util.HexFormat.of().parseHex("34".repeat(32));
        byte[] datumCbor = {(byte) 0xd8, 0x79, (byte) 0x80};
        ArchiveIdentity identity = new ArchiveIdentity(UUID.randomUUID(), "sqlite", 1, 1, "fixture");
        ArchiveBackend backend = new SqliteArchiveBackendProvider().open(identity, temp,
                Map.of("database.path", temp.resolve("utxo-content.sqlite").toString()));
        RocksDbHotHistoryStore hot = new RocksDbHotHistoryStore(temp.resolve("utxo-content-hot"));
        ArchiveRow address = new ArchiveRow("addresses", Arrays.asList(addressKey, new byte[] {0x60},
                null, 0, "enterprise", null, null, "none", null, null,
                null, null, null, 10L, 100L, 0L));
        ArchiveRow output = new ArchiveRow("transaction_outputs", Arrays.asList(
                txHash, 0, 0, "ordinary", addressKey, null, null, 10L,
                "none", null, null, null, null, null, false, blockHash, 10L, 100L, 0L,
                1_700_000_000L, UUID.randomUUID()));
        ArchiveRow datum = new ArchiveRow("transaction_datums", List.of(txHash, 0, datumHash, datumCbor,
                blockHash, 10L, 100L, 0L, 1_700_000_000L, UUID.randomUUID()));
        ArchiveRow redeemer = new ArchiveRow("transaction_redeemers", Arrays.asList(txHash, 0, "spend", 0,
                new byte[] {0x01}, null, java.math.BigInteger.ONE, java.math.BigInteger.TWO,
                blockHash, 10L, 100L, 0L, 1_700_000_000L, UUID.randomUUID()));
        hot.applyBlock(ArchiveDatasetId.UTXO_HISTORY,
                new HotBlockCheckpoint(10, 100, blockHash, new byte[32]),
                List.of(HotArchiveRows.put(ArchiveDatasetId.UTXO_HISTORY, address),
                        HotArchiveRows.put(ArchiveDatasetId.UTXO_HISTORY, output),
                        HotArchiveRows.put(ArchiveDatasetId.UTXO_HISTORY, datum),
                        HotArchiveRows.put(ArchiveDatasetId.UTXO_HISTORY, redeemer)),
                new ArchiveProgress(ArchiveDatasetId.UTXO_HISTORY, ArchiveTrack.LIVE,
                        10, 100, blockHash, 0));

        ChainQuery chain = mock(ChainQuery.class);
        when(chain.getCanonicalBlockReference(10)).thenReturn(Optional.of(
                new CanonicalBlockReference(10, 100, blockHash)));
        var activations = new ActivationStore(temp.resolve("utxo-content-activation.properties"));
        activations.putIfAbsent(ArchiveDatasetId.UTXO_HISTORY, ArchiveTrack.LIVE, 10);
        var configuration = new ArchiveConfiguration(true, temp, ArchiveEngine.SQLITE,
                ArchiveStartMode.TIP, true, ArchiveWorkerConfig.defaults(),
                ArchiveSafetyWindows.resolve(1, 1L, 1L),
                Map.of(ArchiveDatasetId.TRANSACTION,
                                new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0),
                        ArchiveDatasetId.UTXO_HISTORY,
                                new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0)));
        var service = new HistoryArchiveService(mock(Config.class));
        set(service, "backend", backend);
        set(service, "controlStore", hot);
        set(service, "chain", chain);
        set(service, "archiveConfig", configuration);
        set(service, "activations", activations);

        service.promoteLiveRows(ArchiveDatasetId.UTXO_HISTORY, 10);

        try (var read = backend.openReadSession()) {
            var repository = backend.repositories().records(ArchiveDatasetId.UTXO_HISTORY);
            assertThat(repository.query(read, new ArchiveQuery(new BlockRange(10, 10),
                    Map.of("__table", "transaction_datums", "datum_hash", datumHash),
                    ArchivePageCursor.Order.ASC, 10, Optional.empty())).rows()).hasSize(1);
            assertThat(repository.query(read, new ArchiveQuery(new BlockRange(10, 10),
                    Map.of("__table", "transaction_redeemers", "tx_hash", txHash),
                    ArchivePageCursor.Order.ASC, 10, Optional.empty())).rows()).hasSize(1);
        }
        try (var current = hot.snapshot()) {
            assertThat(HotArchiveRows.allRows(current, ArchiveDatasetId.UTXO_HISTORY,
                    "transaction_datums")).isEmpty();
            assertThat(HotArchiveRows.allRows(current, ArchiveDatasetId.UTXO_HISTORY,
                    "transaction_redeemers")).isEmpty();
        }

        byte[] nextBlockHash = java.util.HexFormat.of().parseHex("36".repeat(32));
        byte[] newDatumHash = java.util.HexFormat.of().parseHex("37".repeat(32));
        byte[] nextTxHash = java.util.HexFormat.of().parseHex("38".repeat(32));
        ArchiveRow newDatum = new ArchiveRow("transaction_datums", List.of(nextTxHash, 0, newDatumHash,
                new byte[] {0x03}, nextBlockHash, 11L, 101L, 0L, 1_700_000_001L, UUID.randomUUID()));
        ArchiveRow newRedeemer = new ArchiveRow("transaction_redeemers", Arrays.asList(nextTxHash, 0,
                "mint", 0, new byte[] {0x04}, null, java.math.BigInteger.ONE, java.math.BigInteger.TWO,
                nextBlockHash, 11L, 101L, 0L, 1_700_000_001L, UUID.randomUUID()));
        hot.applyBlock(ArchiveDatasetId.UTXO_HISTORY,
                new HotBlockCheckpoint(11, 101, nextBlockHash, blockHash),
                List.of(HotArchiveRows.put(ArchiveDatasetId.UTXO_HISTORY, newDatum),
                        HotArchiveRows.put(ArchiveDatasetId.UTXO_HISTORY, newRedeemer)),
                new ArchiveProgress(ArchiveDatasetId.UTXO_HISTORY, ArchiveTrack.LIVE,
                        11, 101, nextBlockHash, 0));

        // Cold coverage already owns the finalized range, so this takes the
        // Cleanup of finalized block 10 must leave block 11 payload rows hot.
        service.promoteLiveRows(ArchiveDatasetId.UTXO_HISTORY, 10);
        try (var current = hot.snapshot()) {
            assertThat(HotArchiveRows.allRows(current, ArchiveDatasetId.UTXO_HISTORY, "transaction_datums"))
                    .extracting(row -> java.util.HexFormat.of().formatHex((byte[]) row.value("datum_hash")))
                    .containsExactly(java.util.HexFormat.of().formatHex(newDatumHash));
            assertThat(HotArchiveRows.allRows(current, ArchiveDatasetId.UTXO_HISTORY, "transaction_redeemers"))
                    .extracting(row -> java.util.HexFormat.of().formatHex((byte[]) row.value("tx_hash")))
                    .containsExactly(java.util.HexFormat.of().formatHex(nextTxHash));
        }

        backend.close();
        hot.close();
    }

    @Test
    void compatibilityQueryRejectsColdLiveCoverageGap() throws Exception {
        byte[] stake = new byte[28];
        Arrays.fill(stake, (byte) 3);
        byte[] coldHash = new byte[32];
        coldHash[0] = 1;
        byte[] liveHash = new byte[32];
        liveHash[0] = 2;
        ArchiveBackend backend = new SqliteArchiveBackendProvider().open(
                new ArchiveIdentity(UUID.randomUUID(), "sqlite", 1, 1, "fixture"), temp,
                Map.of("database.path", temp.resolve("gap.sqlite").toString()));
        RocksDbHotHistoryStore hot = new RocksDbHotHistoryStore(temp.resolve("gap-hot"));
        ArchiveJob coldJob = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "fixture"),
                ArchiveDatasetId.ACCOUNT_EVENT,
                com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas
                        .schema(ArchiveDatasetId.ACCOUNT_EVENT).projectionVersion(), new BlockRange(0, 0),
                new ArchiveRangeAnchor(0, coldHash, 0, coldHash), "fixture-v1");
        try (var write = backend.begin(coldJob)) {
            write.append(accountEvent(stake, coldHash, 0, coldJob.jobId()));
            write.commit();
        }
        ArchiveRow live = accountEvent(stake, liveHash, 10, UUID.randomUUID());
        hot.applyBlock(ArchiveDatasetId.ACCOUNT_EVENT,
                new HotBlockCheckpoint(10, 100, liveHash, new byte[32]),
                List.of(HotArchiveRows.put(ArchiveDatasetId.ACCOUNT_EVENT, live)),
                new ArchiveProgress(ArchiveDatasetId.ACCOUNT_EVENT, ArchiveTrack.LIVE,
                        10, 100, liveHash, 0));
        var activations = new ActivationStore(temp.resolve("gap-activation.properties"));
        activations.putIfAbsent(ArchiveDatasetId.ACCOUNT_EVENT, ArchiveTrack.LIVE, 10);
        var configuration = new ArchiveConfiguration(true, temp, ArchiveEngine.SQLITE,
                ArchiveStartMode.TIP, true, ArchiveWorkerConfig.defaults(),
                ArchiveSafetyWindows.resolve(1, 1L, 1L),
                Map.of(ArchiveDatasetId.ACCOUNT_EVENT,
                        new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0)));
        var service = new HistoryArchiveService(mock(Config.class));
        set(service, "backend", backend);
        set(service, "controlStore", hot);
        set(service, "archiveConfig", configuration);
        set(service, "activations", activations);

        assertThatThrownBy(() -> service.accountHistoryProvider().getWithdrawals(
                0, java.util.HexFormat.of().formatHex(stake), 1, 20, "asc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cold/live gap");

        backend.close();
        hot.close();
    }

    @Test
    void compatibilityQueryRejectsUnboundedPageBeforeAllocating() {
        var service = new HistoryArchiveService(mock(Config.class));
        assertThatThrownBy(() -> service.accountHistoryProvider().getWithdrawals(
                0, "00".repeat(28), Integer.MAX_VALUE, 100, "asc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded lookup window");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void failedLiveRollbackIsRearmedInsteadOfSilentlyConsumed() throws Exception {
        RocksDbHotHistoryStore hot = new RocksDbHotHistoryStore(temp.resolve("rollback-retry-hot"));
        ArchiveBackend backend = mock(ArchiveBackend.class);
        when(backend.coverage(ArchiveDatasetId.TRANSACTION))
                .thenThrow(new ArchiveStoreException("temporary backend failure"));
        ChainQuery chain = mock(ChainQuery.class);
        when(chain.getCanonicalBlockReference(anyLong())).thenAnswer(invocation -> {
            long block = invocation.getArgument(0);
            return Optional.of(new CanonicalBlockReference(block, block, new byte[] {(byte) block}));
        });
        var service = new HistoryArchiveService(mock(Config.class));
        set(service, "backend", backend);
        set(service, "controlStore", hot);
        set(service, "chain", chain);
        Map workers = (Map) get(service, "blockWorkers");
        workers.put(ArchiveDatasetId.TRANSACTION, null);
        AtomicLong pending = (AtomicLong) get(service, "pendingRollbackSlot");
        pending.set(5);

        var process = HistoryArchiveService.class.getDeclaredMethod("processPendingLiveRollback", long.class);
        process.setAccessible(true);
        process.invoke(service, 10L);

        assertThat(pending.get()).isEqualTo(5L);
        hot.close();
    }

    private static ArchiveRow accountEvent(byte[] stake, byte[] blockHash, long block, UUID jobId) {
        byte[] txHash = new byte[32];
        txHash[0] = (byte) (block + 1);
        return new ArchiveRow("account_events", Arrays.asList(stake, "key", "withdrawal", txHash,
                blockHash, block, block * 10, 0L, 1_700_000_000L + block, 0, 0L,
                null, null, null, 5L, jobId));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
