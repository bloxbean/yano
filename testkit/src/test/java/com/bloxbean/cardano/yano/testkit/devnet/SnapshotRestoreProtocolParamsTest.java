package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for issue #26: protocol parameters endpoint returns 404 after
 * restoring from a RocksDB snapshot when epoch-param tracking is enabled
 * (the app devnet profile default).
 */
class SnapshotRestoreProtocolParamsTest {

    private static YanoDevnetTestConfig.Builder trackedConfig() {
        return YanoDevnetTestConfig.builder()
                .epochLength(30)
                .runtimeOption(YanoPropertyKeys.AccountState.ENABLED, true)
                .runtimeOption(YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, true)
                .runtimeOption(YanoPropertyKeys.Ledger.ADAPOT_ENABLED, true)
                .runtimeOption(YanoPropertyKeys.Ledger.REWARDS_ENABLED, true)
                .runtimeOption(YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED, true)
                .runtimeOption(YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED, true);
    }

    @Test
    void protocolParamsAvailableAfterRuntimeSnapshotRestore() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(trackedConfig().build())) {
            kit.start();
            kit.await().untilBlockAtLeast(1);

            int epochBefore = (int) kit.queries().currentEpoch();
            assertTrue(kit.queries().protocolParameters(epochBefore).isPresent(),
                    "protocol params should be available before snapshot (epoch " + epochBefore + ")");

            kit.snapshots().create("s1");
            kit.snapshots().restore("s1");

            int epochAfter = (int) kit.queries().currentEpoch();
            assertTrue(kit.queries().protocolParameters(epochAfter).isPresent(),
                    "protocol params should be available after restore (epoch " + epochAfter + ")");
        }
    }

    @Test
    void protocolParamsAvailableAfterRestoreToEarlierEpoch() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(trackedConfig().build())) {
            kit.start();
            kit.await().untilBlockAtLeast(1);

            kit.snapshots().create("early");
            kit.time().advanceToEpoch(kit.queries().currentEpoch() + 2);
            int laterEpoch = (int) kit.queries().currentEpoch();
            assertTrue(kit.queries().protocolParameters(laterEpoch).isPresent(),
                    "protocol params should be available after epoch advance (epoch " + laterEpoch + ")");

            kit.snapshots().restore("early");

            int epochAfter = (int) kit.queries().currentEpoch();
            assertTrue(kit.queries().protocolParameters(epochAfter).isPresent(),
                    "protocol params should be available after restore back (epoch " + epochAfter + ")");
        }
    }

    @Test
    void protocolParamsAvailableAfterOfflineRestoreAndRestart(@TempDir Path tempDir) throws Exception {
        Path storage = tempDir.resolve("chainstate");
        Path snapshotCheckpoint;

        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(
                trackedConfig().persistentRocksDbStorage(storage).build())) {
            kit.start();
            kit.await().untilBlockAtLeast(1);

            int epoch = (int) kit.queries().currentEpoch();
            assertTrue(kit.queries().protocolParameters(epoch).isPresent(),
                    "protocol params should be available before snapshot (epoch " + epoch + ")");

            kit.snapshots().create("offline");
            snapshotCheckpoint = storage.getParent().resolve("snapshots").resolve("offline").resolve("checkpoint");
            assertTrue(Files.isDirectory(snapshotCheckpoint), "checkpoint dir should exist");
        }

        // Offline restore: replace the chainstate dir with the checkpoint content
        deleteRecursively(storage);
        copyRecursively(snapshotCheckpoint, storage);

        // Start the application again on the restored DB
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(
                trackedConfig().persistentRocksDbStorage(storage).build())) {
            kit.start();
            kit.await().untilRunning();

            int epoch = (int) kit.queries().currentEpoch();
            assertTrue(kit.queries().protocolParameters(epoch).isPresent(),
                    "protocol params should be available after offline restore + restart (epoch " + epoch + ")");
        }
    }

    /**
     * The issue #26 shape: after a restore, the devnet producer resumes at the
     * wall-clock slot and emits one collapsed multi-epoch transition, so the
     * skipped epochs get no finalized entries in the epoch-param tracker.
     * Carry-forward lookup (dev mode) must still resolve them.
     */
    @Test
    void protocolParamsAvailableForGapEpochsAfterWallClockJump() throws Exception {
        // Short wall-clock epochs (5 slots x 1s) so a real sleep spans multiple boundaries
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(trackedConfig().epochLength(5).build())) {
            kit.start();
            kit.await().untilBlockAtLeast(1);

            int snapshotEpoch = (int) kit.queries().currentEpoch();
            kit.snapshots().create("gap");

            // Let the wall clock run past at least two epoch boundaries, then restore.
            Thread.sleep(12_000);
            kit.snapshots().restore("gap");

            kit.await().withTimeout(Duration.ofSeconds(30)).until(
                    () -> kit.queries().currentEpoch() >= snapshotEpoch + 2,
                    "producer to jump past restored epoch " + snapshotEpoch);

            int currentEpoch = (int) kit.queries().currentEpoch();
            for (int epoch = snapshotEpoch; epoch <= currentEpoch; epoch++) {
                assertTrue(kit.queries().protocolParameters(epoch).isPresent(),
                        "protocol params should resolve for epoch " + epoch
                                + " after restore-induced jump from epoch " + snapshotEpoch);
            }
        }
    }

    /**
     * With process-skipped-epochs enabled, the same restore-induced jump runs full
     * boundary processing for every skipped epoch. Verified via per-epoch AdaPot
     * entries — unlike protocol params, those are never carry-forward-resolved, so
     * their presence proves each boundary actually ran.
     */
    @Test
    void skippedEpochsFullyProcessedWhenProcessSkippedEpochsEnabled() throws Exception {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(trackedConfig()
                .epochLength(5)
                .runtimeOption(YanoPropertyKeys.BlockProducer.PROCESS_SKIPPED_EPOCHS, true)
                .build())) {
            kit.start();
            kit.await().untilBlockAtLeast(1);

            int snapshotEpoch = (int) kit.queries().currentEpoch();
            kit.snapshots().create("loop");

            Thread.sleep(12_000);
            kit.snapshots().restore("loop");

            kit.await().withTimeout(Duration.ofSeconds(30)).until(
                    () -> kit.queries().currentEpoch() >= snapshotEpoch + 2,
                    "producer to jump past restored epoch " + snapshotEpoch);

            int currentEpoch = (int) kit.queries().currentEpoch();
            var ledgerState = kit.ledger().getLedgerStateProvider();
            for (int epoch = snapshotEpoch + 1; epoch <= currentEpoch; epoch++) {
                assertTrue(kit.queries().protocolParameters(epoch).isPresent(),
                        "protocol params should resolve for epoch " + epoch);
                assertTrue(ledgerState.getAdaPot(epoch).isPresent(),
                        "AdaPot should exist for skipped epoch " + epoch
                                + " when process-skipped-epochs is enabled");
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyRecursively(Path src, Path dest) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dest.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dest.resolve(src.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
