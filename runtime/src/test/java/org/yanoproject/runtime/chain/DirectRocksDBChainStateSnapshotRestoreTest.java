package org.yanoproject.runtime.chain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectRocksDBChainStateSnapshotRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void restoreKeepsDbDirectoryAndReturnsToSnapshotTip() throws IOException {
        Path dbDir = tempDir.resolve("chainstate");
        Path snapshot = tempDir.resolve("snapshot");

        try (var chainState = new DirectRocksDBChainState(dbDir.toString())) {
            Object directoryKey = fileKey(dbDir);
            assumeTrue(directoryKey != null, "file keys are not available on this platform");

            storeMain(chainState, 1L, 10L);
            chainState.createSnapshot(snapshot.toString());
            storeMain(chainState, 2L, 20L);

            chainState.restoreFromSnapshot(snapshot.toString());

            // A mount point cannot be removed, so restore must reuse the directory itself.
            assertThat(fileKey(dbDir)).isEqualTo(directoryKey);
            assertThat(chainState.getTip().getBlockNumber()).isEqualTo(1L);
            assertThat(chainState.getBlock(hash(2))).isNull();
        }
    }

    @Test
    void restoreThroughSymlinkedDbPathKeepsLinkAndRestoresIntoTarget() throws IOException {
        Path target = Files.createDirectory(tempDir.resolve("disk"));
        Path link = tempDir.resolve("chainstate");
        Path snapshot = tempDir.resolve("snapshot");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links are not available: " + e.getMessage());
        }

        try (var chainState = new DirectRocksDBChainState(link.toString())) {
            storeMain(chainState, 1L, 10L);
            chainState.createSnapshot(snapshot.toString());
            storeMain(chainState, 2L, 20L);

            chainState.restoreFromSnapshot(snapshot.toString());

            assertThat(Files.isSymbolicLink(link)).isTrue();
            assertThat(target.resolve("CURRENT")).exists();
            assertThat(chainState.getTip().getBlockNumber()).isEqualTo(1L);
        }
    }

    private static void storeMain(DirectRocksDBChainState chainState, long number, long slot) {
        byte[] hash = hash((int) number);
        chainState.storeBlockHeader(hash, number, slot, new byte[]{hash[31]});
        chainState.storeBlock(hash, number, slot, new byte[]{hash[31]});
    }

    private static Object fileKey(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
    }

    private static byte[] hash(int value) {
        byte[] hash = new byte[32];
        hash[31] = (byte) value;
        return hash;
    }
}
