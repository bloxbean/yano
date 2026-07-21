package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceBundle;
import com.bloxbean.cardano.yano.api.appchain.evidence.EvidenceVerifier;
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
 * ADR-006 E4.4: pruning message bodies keeps headers, ids, roots and certs —
 * block hashes, the prev-hash chain, inclusion proofs and evidence stay valid;
 * only the payloads (data-minimization / crypto-shredding) are dropped.
 */
@Timeout(60)
class AppChainRetentionTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainRetentionTest.class);
    private static final byte[] KEY_A = seed(140);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void prunedBodies_keepProofsAndEvidenceValid() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("retention-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // Produce several blocks (one message each)
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String id = node.submit("t", ("payload-" + i).getBytes(StandardCharsets.UTF_8));
            ids.add(id);
            int height = i;
            awaitTrue("block " + height, () -> node.tipHeight() >= height);
        }
        long tip = node.tipHeight();
        byte[] rootBefore = node.stateRoot();

        String firstId = ids.get(0);
        long firstHeight = node.messageHeight(HexUtil.decodeHexString(firstId)).orElseThrow();

        // Prune bodies below the tip (simulating an L1_FINAL anchor at the tip)
        int pruned = node.pruneBodiesBelowForTesting(tip - 1);
        assertThat(pruned).isGreaterThan(0);

        // Body of the first block is gone...
        AppBlock prunedBlock = node.block(firstHeight).orElseThrow();
        assertThat(prunedBlock.messages().get(0).getSize()).isEqualTo(0);
        // ...but the id, messages-root, state-root, prev-hash and cert survive
        assertThat(prunedBlock.messages().get(0).getMessageId())
                .isEqualTo(HexUtil.decodeHexString(firstId));
        assertThat(prunedBlock.cert().signatures()).isNotEmpty();

        // State root and tip unchanged (MPF is current-state, untouched)
        assertThat(node.stateRoot()).isEqualTo(rootBefore);
        assertThat(node.tipHeight()).isEqualTo(tip);

        // Inclusion proof for the pruned message's state key still verifies
        assertThat(node.stateProof(HexUtil.decodeHexString(firstId))).isPresent();

        // Evidence for the pruned message still verifies (finality + inclusion)
        EvidenceBundle bundle = node.evidence(HexUtil.decodeHexString(firstId)).orElseThrow();
        EvidenceVerifier.Result retained = EvidenceVerifier.verify(
                bundle, "retention-chain", Set.of(pubA), 1);
        assertThat(retained.valid()).isTrue();
        assertThat(retained.messageContentVerified()).isFalse();

        // Idempotent: re-pruning the same range is a no-op
        assertThat(node.pruneBodiesBelowForTesting(tip - 1)).isEqualTo(0);
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
