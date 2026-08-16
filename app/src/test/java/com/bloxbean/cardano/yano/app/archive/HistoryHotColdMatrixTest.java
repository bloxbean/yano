package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.*;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.*;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.worker.*;
import com.bloxbean.cardano.yano.archive.ducklake.DuckLakeArchiveBackendProvider;
import com.bloxbean.cardano.yano.archive.sqlite.*;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HistoryHotColdMatrixTest {
    @TempDir Path temp;

    @Test void everyHotAndDurableEngineCombinationPromotesTheSameLogicalFact() throws Exception {
        for (String hotEngine : List.of("rocksdb", "sqlite")) {
            for (String archiveEngine : List.of("sqlite", "ducklake")) {
                verifyCombination(hotEngine, archiveEngine);
            }
        }
    }

    private void verifyCombination(String hotEngine, String archiveEngine) throws Exception {
        Path root = temp.resolve(hotEngine + "-" + archiveEngine);
        byte[] txHash = hash(hotEngine.hashCode() ^ archiveEngine.hashCode());
        byte[] blockHash = hash(41);
        HotHistoryStore hot = hotEngine.equals("sqlite")
                ? new SqliteHotHistoryStore(root.resolve("hot-history.sqlite"))
                : new RocksDbHotHistoryStore(root.resolve("hot-rocksdb"));
        ArchiveIdentity identity = new ArchiveIdentity(UUID.randomUUID(), archiveEngine, 1, 1, "fixture");
        ArchiveBackend cold = archiveEngine.equals("sqlite")
                ? new SqliteArchiveBackendProvider().open(identity, root,
                        Map.of("database.path", root.resolve("history.sqlite").toString()))
                : new DuckLakeArchiveBackendProvider().open(identity, root, Map.of());
        try {
            ArchiveRow row = new ArchiveRow("chain_transaction", Arrays.asList(txHash, blockHash, 10L, 100L,
                    0L, 1_700_000_000L, 0, true, 5L, new UUID(0, 1)));
            hot.applyBlock(ArchiveDatasetId.TRANSACTION,
                    new HotBlockCheckpoint(10, 100, blockHash, hash(40)),
                    List.of(new HotHistoryOperation.Fact(row)),
                    new ArchiveProgress(ArchiveDatasetId.TRANSACTION, ArchiveTrack.LIVE,
                            10, 100, blockHash, 0));
            ChainQuery chain = mock(ChainQuery.class);
            when(chain.getCanonicalBlockReference(10)).thenReturn(Optional.of(
                    new CanonicalBlockReference(10, 100, blockHash)));
            ActivationStore activations = new ActivationStore(root.resolve("activation.properties"));
            activations.putIfAbsent(ArchiveDatasetId.TRANSACTION, 10);
            ArchiveConfiguration configuration = new ArchiveConfiguration(true, root,
                    archiveEngine.equals("sqlite") ? ArchiveEngine.SQLITE : ArchiveEngine.DUCKLAKE,
                    ArchiveStartMode.TIP, ArchiveWorkerConfig.defaults(),
                    ArchiveSafetyWindows.resolve(1, 1L, 1L),
                    Map.of(ArchiveDatasetId.TRANSACTION,
                            new DatasetArchiveConfig(true, ArchiveStartMode.TIP, 0)));
            HistoryArchiveService service = new HistoryArchiveService(mock(Config.class));
            set(service, "backend", cold); set(service, "controlStore", hot);
            set(service, "chain", chain); set(service, "archiveConfig", configuration);
            set(service, "activations", activations);

            service.promoteLiveRows(ArchiveDatasetId.TRANSACTION, 10);

            assertThat(cold.coverage(ArchiveDatasetId.TRANSACTION).covers(10))
                    .as(hotEngine + " hot -> " + archiveEngine + " archive").isTrue();
            try (ArchiveReadSession read = cold.openReadSession()) {
                assertThat(cold.findTransaction(read, txHash)).isPresent();
            }
            try (HotHistorySnapshot read = hot.snapshot()) {
                assertThat(read.scanTable(ArchiveDatasetId.TRANSACTION, "chain_transaction")).isEmpty();
            }
        } finally {
            cold.close(); hot.close();
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = HistoryArchiveService.class.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private static byte[] hash(int marker) {
        byte[] value = new byte[32]; Arrays.fill(value, (byte) marker); return value;
    }
}
