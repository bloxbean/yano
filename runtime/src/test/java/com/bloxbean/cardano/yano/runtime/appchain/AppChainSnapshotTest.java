package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E5.3: an atomic ledger snapshot restores full state on a fresh node
 * with no replay — same tip, same state root, same proofs — and the integrity
 * check accepts it.
 */
@Timeout(60)
class AppChainSnapshotTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainSnapshotTest.class);
    private static final byte[] KEY_A = seed(150);

    @TempDir
    Path tempDir;

    private AppChainSubsystem source;

    @AfterEach
    void tearDown() {
        if (source != null) source.stop();
    }

    @Test
    void snapshotRestore_reproducesStateWithoutReplay() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("snap-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        source = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("source").toString(), null, log);
        source.start();

        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(source.submit("t", ("m-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        awaitTrue("all finalized", () -> ids.stream()
                .allMatch(id -> source.messageHeight(HexUtil.decodeHexString(id)).isPresent()));
        long sourceTip = source.tipHeight();
        byte[] sourceRoot = source.stateRoot();

        // Snapshot the ledger to a fresh directory
        Path snapshotDir = tempDir.resolve("snapshot");
        long snapHeight = source.snapshot(snapshotDir.toString());
        assertThat(snapHeight).isEqualTo(sourceTip);

        // Open a fresh subsystem whose ledger is the restored snapshot.
        // Subsystem appends "/<chainId>" to the base path, so place the snapshot
        // where that resolves to it.
        Path restoreBase = tempDir.resolve("restore");
        java.nio.file.Files.createDirectories(restoreBase);
        copyDir(snapshotDir, restoreBase.resolve("snap-chain"));

        AppChainSubsystem restored = new AppChainSubsystem(config, 42, null, null,
                restoreBase.toString(), null, log);
        restored.start();
        try {
            // Full state present with NO replay: same tip, same root
            assertThat(restored.tipHeight()).isEqualTo(sourceTip);
            assertThat(restored.stateRoot()).isEqualTo(sourceRoot);

            // Every message is present and provable on the restored node
            for (String id : ids) {
                assertThat(restored.messageHeight(HexUtil.decodeHexString(id))).isPresent();
                assertThat(restored.stateProof(HexUtil.decodeHexString(id))).isPresent();
            }
            // Blocks are byte-identical
            assertThat(restored.block(sourceTip).orElseThrow().stateRoot())
                    .isEqualTo(source.block(sourceTip).orElseThrow().stateRoot());
        } finally {
            restored.stop();
        }
    }

    private static void copyDir(Path src, Path dest) throws Exception {
        java.nio.file.Files.walk(src).forEach(path -> {
            try {
                Path target = dest.resolve(src.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) {
                    java.nio.file.Files.createDirectories(target);
                } else {
                    java.nio.file.Files.createDirectories(target.getParent());
                    java.nio.file.Files.copy(path, target);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
