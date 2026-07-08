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
 * ADR-006 E4.5: staged member-key rotation — add, re-threshold, retire — with
 * guard rails, and the rotated state persisting across a restart (override
 * wins over the static config).
 */
@Timeout(60)
class AppChainKeyRotationTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainKeyRotationTest.class);
    private static final byte[] KEY_A = seed(211);
    private static final byte[] KEY_B = seed(212);
    private static final byte[] KEY_C = seed(213);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void stagedRotation_guards_andPersistsAcrossRestart() throws Exception {
        String pubA = pub(KEY_A);
        String pubB = pub(KEY_B);
        String pubC = pub(KEY_C);

        AppChainConfig config = AppChainConfig.builder("rotate-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA, pubB))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // Chain still works before/after rotation
        String id1 = node.submit("t", "before".getBytes(StandardCharsets.UTF_8));
        awaitTrue("pre-rotation finalized",
                () -> node.messageHeight(HexUtil.decodeHexString(id1)).isPresent());

        assertThat(node.members()).containsExactlyInAnyOrder(pubA, pubB);
        assertThat(node.effectiveThreshold()).isEqualTo(1);

        // Stage 1: add C (idempotent)
        node.addMember(pubC);
        node.addMember(pubC);
        assertThat(node.members()).containsExactlyInAnyOrder(pubA, pubB, pubC);

        // Stage 2: raise threshold
        node.setThreshold(2);
        assertThat(node.effectiveThreshold()).isEqualTo(2);
        assertThatThrownBy(() -> node.setThreshold(4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> node.setThreshold(0))
                .isInstanceOf(IllegalArgumentException.class);

        // Guard: can't remove the proposer
        assertThatThrownBy(() -> node.removeMember(pubA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proposer");
        // Guard: can't drop below threshold (3 members, threshold 2 → removing ok;
        // then removing another would leave 1 < 2)
        node.removeMember(pubB);
        assertThat(node.members()).containsExactlyInAnyOrder(pubA, pubC);
        assertThatThrownBy(() -> node.removeMember(pubC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below threshold");
        // Guard: unknown member
        assertThatThrownBy(() -> node.removeMember(pubB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a member");

        // Back to a workable single-node setup and prove the chain still runs
        node.setThreshold(1);
        String id2 = node.submit("t", "after".getBytes(StandardCharsets.UTF_8));
        awaitTrue("post-rotation finalized",
                () -> node.messageHeight(HexUtil.decodeHexString(id2)).isPresent());

        // status reflects the rotated group
        assertThat(node.status().get("members")).isEqualTo(2);
        assertThat(node.status().get("threshold")).isEqualTo(1);

        // Restart: the rotated override (A, C @ threshold 1) wins over the
        // static config (A, B @ threshold 1)
        node.stop();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();
        assertThat(node.members()).containsExactlyInAnyOrder(pubA, pubC);
        assertThat(node.effectiveThreshold()).isEqualTo(1);

        // And it still finalizes after the restart
        String id3 = node.submit("t", "post-restart".getBytes(StandardCharsets.UTF_8));
        awaitTrue("post-restart finalized",
                () -> node.messageHeight(HexUtil.decodeHexString(id3)).isPresent());
    }

    private static String pub(byte[] key) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(key));
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
