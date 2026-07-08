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
import java.util.Arrays;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-006 E5.4 admin API: pause/resume local submissions, drain the pending
 * pool, force-anchor (no-op without anchoring).
 */
@Timeout(60)
class AppChainAdminTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainAdminTest.class);
    private static final byte[] KEY_A = seed(191);
    private static final byte[] KEY_B = seed(192);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void pauseDrainResume_andForceAnchorNoop() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        String pubB = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_B));
        // Proposer is the OTHER member (pubB, not running) so submitted messages
        // stay in the pool — lets us observe drainPool() deterministically.
        AppChainConfig config = AppChainConfig.builder("admin-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA, pubB))
                .proposerKeyHex(pubB)
                .threshold(2)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // Not paused by default
        assertThat(node.submissionsPaused()).isFalse();
        assertThat(node.status().get("submissionsPaused")).isEqualTo(false);

        // Submissions land in the pool (no proposer running → not finalized)
        node.submit("t", "m1".getBytes(StandardCharsets.UTF_8));
        node.submit("t", "m2".getBytes(StandardCharsets.UTF_8));
        awaitTrue("pool has 2", () -> (int) node.status().get("poolSize") == 2);

        // Pause → submit rejected
        node.pauseSubmissions();
        assertThat(node.submissionsPaused()).isTrue();
        assertThatThrownBy(() -> node.submit("t", "m3".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paused");

        // Drain → pending dropped
        assertThat(node.drainPool()).isEqualTo(2);
        assertThat((int) node.status().get("poolSize")).isZero();

        // Resume → submissions accepted again
        node.resumeSubmissions();
        assertThat(node.submissionsPaused()).isFalse();
        node.submit("t", "m4".getBytes(StandardCharsets.UTF_8));
        awaitTrue("pool has 1", () -> (int) node.status().get("poolSize") == 1);

        // Force-anchor without anchoring configured → false, no exception
        assertThat(node.forceAnchor()).isFalse();

        // Idempotent pause/resume
        node.pauseSubmissions();
        node.pauseSubmissions();
        node.resumeSubmissions();
        node.resumeSubmissions();
        assertThat(node.submissionsPaused()).isFalse();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
