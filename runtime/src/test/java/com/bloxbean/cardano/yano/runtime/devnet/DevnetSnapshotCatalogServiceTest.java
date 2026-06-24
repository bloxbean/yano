package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevnetSnapshotCatalogServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createRequiresDevModeBeforeResolvingStore() {
        AtomicBoolean resolvedStore = new AtomicBoolean();
        var service = new DevnetSnapshotCatalogService(
                () -> false,
                () -> true,
                unsupportedChainState(),
                () -> {
                    resolvedStore.set(true);
                    throw new AssertionError("store should not be resolved");
                },
                () -> {
                    throw new AssertionError("required store should not be resolved");
                },
                () -> null);

        var error = assertThrows(IllegalStateException.class, () -> service.create("snap"));

        assertEquals("Snapshot requires dev mode (yano.dev-mode=true)", error.getMessage());
        assertFalse(resolvedStore.get());
    }

    @Test
    void createRequiresDevnetProductionBeforeResolvingStore() {
        AtomicBoolean resolvedStore = new AtomicBoolean();
        var service = new DevnetSnapshotCatalogService(
                () -> true,
                () -> false,
                unsupportedChainState(),
                () -> {
                    resolvedStore.set(true);
                    throw new AssertionError("store should not be resolved");
                },
                () -> {
                    throw new AssertionError("required store should not be resolved");
                },
                () -> null);

        var error = assertThrows(IllegalStateException.class, () -> service.create("snap"));

        assertEquals("Snapshot requires block producer to be running", error.getMessage());
        assertFalse(resolvedStore.get());
    }

    @Test
    void listReturnsEmptyWhenSnapshotsUnsupported() {
        var service = new DevnetSnapshotCatalogService(
                () -> false,
                () -> false,
                unsupportedChainState(),
                () -> null,
                () -> {
                    throw new IllegalStateException("old message");
                },
                () -> null);

        assertTrue(service.list().isEmpty());
    }

    @Test
    void createUsesRequiredSnapshotSupplierForUnsupportedStoreMessage() {
        var service = new DevnetSnapshotCatalogService(
                () -> true,
                () -> true,
                unsupportedChainState(),
                () -> null,
                () -> {
                    throw new IllegalStateException("old message");
                },
                () -> null);

        var error = assertThrows(IllegalStateException.class, () -> service.create("snap"));

        assertEquals("old message", error.getMessage());
    }

    @Test
    void deleteUsesSnapshotStoreValidation() {
        try (DirectRocksDBChainState chainState = new DirectRocksDBChainState(
                tempDir.resolve("chainstate").toString())) {
            var service = new DevnetSnapshotCatalogService(
                    () -> false,
                    () -> false,
                    chainState,
                    () -> chainState,
                    () -> chainState,
                    () -> null);

            var error = assertThrows(IllegalArgumentException.class, () -> service.delete("../escape"));

            assertEquals("Invalid snapshot name: ../escape", error.getMessage());
        }
    }

    @Test
    void createDelegatesToStoreAndListsCreatedSnapshot() {
        try (DirectRocksDBChainState chainState = new DirectRocksDBChainState(
                tempDir.resolve("chainstate").toString())) {
            var service = new DevnetSnapshotCatalogService(
                    () -> true,
                    () -> true,
                    chainState,
                    () -> chainState,
                    () -> chainState,
                    () -> null);

            var created = service.create("snap-1");

            assertEquals("snap-1", created.name());
            assertTrue(Files.isDirectory(tempDir.resolve("snapshots").resolve("snap-1").resolve("checkpoint")));
            assertEquals("snap-1", service.list().getFirst().name());
        }
    }

    private static ChainState unsupportedChainState() {
        return new InMemoryChainState();
    }
}
