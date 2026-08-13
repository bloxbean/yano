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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryArchivePromotionTest {
    @TempDir Path temp;

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

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
