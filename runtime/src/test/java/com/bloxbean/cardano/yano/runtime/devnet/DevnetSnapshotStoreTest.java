package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevnetSnapshotStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void checkpointDirStaysUnderSnapshotRoot() {
        Path dbPath = tempDir.resolve("chainstate");
        try (DirectRocksDBChainState chainState = new DirectRocksDBChainState(dbPath.toString())) {
            DevnetSnapshotStore store = new DevnetSnapshotStore(chainState, chainState, null);

            Path checkpoint = store.checkpointDir("snap-1");

            assertTrue(checkpoint.normalize().startsWith(tempDir.resolve("snapshots").normalize()));
            assertEquals("checkpoint", checkpoint.getFileName().toString());
        }
    }

    @Test
    void rejectsPathLikeSnapshotNames() {
        try (DirectRocksDBChainState chainState = new DirectRocksDBChainState(tempDir.resolve("chainstate").toString())) {
            DevnetSnapshotStore store = new DevnetSnapshotStore(chainState, chainState, null);

            for (String name : new String[]{"../escape", "/tmp/escape", "safe/name", "", ".", ".."}) {
                assertThrows(IllegalArgumentException.class, () -> store.checkpointDir(name), name);
            }
        }
    }
}
