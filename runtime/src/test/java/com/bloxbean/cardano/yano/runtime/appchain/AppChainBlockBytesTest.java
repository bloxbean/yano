package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Block-bytes cap (ADR app-layer/008 block-bytes fix): a backlog larger than
 * one block's byte budget is TRIMMED to fit and drained across multiple blocks
 * — every message finalizes, the chain never stalls, and no finalized block
 * exceeds {@code block.max-bytes}. (The follower-side reject + multi-node no-
 * stall are covered by the cluster load-test gate.)
 */
@Timeout(60)
class AppChainBlockBytesTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainBlockBytesTest.class);
    private static final byte[] KEY_A = seed(93);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            try {
                node.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void largeBacklog_trimsAcrossBlocks_allFinalize_noStall() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        long blockMaxBytes = 4096;
        AppChainConfig config = AppChainConfig.builder("bytes-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)          // fixed proposer = self, threshold 1 self-finalizes
                .threshold(1)
                .blockIntervalMs(300)
                .maxMessageBytes(1024)         // small so blockMaxBytes can be small
                .blockMaxBytes(blockMaxBytes)  // ~a handful of messages per block
                .maxBlockMessages(10_000)      // count is NOT the binding limit here
                .poolMaxMessages(10_000)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // Submit far more than fits one block: 60 × ~300-byte bodies ≈ 31 KB of
        // serialized messages against a 4 KB block cap → must split into several
        // blocks. Each body is distinct so nothing dedups.
        int count = 60;
        List<byte[]> ids = new ArrayList<>();
        byte[] pad = new byte[300];
        Arrays.fill(pad, (byte) 'x');
        for (int i = 0; i < count; i++) {
            byte[] body = (i + ":" + new String(pad, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            ids.add(HexUtil.decodeHexString(node.submit("load", body)));
        }

        // Wait until every message is finalized (or fail on timeout — a stall).
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            long finalized = ids.stream().filter(id -> node.messageHeight(id).isPresent()).count();
            if (finalized == count) {
                break;
            }
            Thread.sleep(200);
        }

        long finalized = ids.stream().filter(id -> node.messageHeight(id).isPresent()).count();
        assertThat(finalized)
                .as("all messages must finalize (no stall) — the backlog is trimmed across blocks")
                .isEqualTo(count);

        long tip = node.tipHeight();
        assertThat(tip).as("the backlog must span multiple blocks, not one oversized block").isGreaterThan(1);

        // No finalized block exceeds the byte cap.
        for (long h = 1; h <= tip; h++) {
            var block = node.block(h);
            if (block.isEmpty()) {
                continue;
            }
            int size = AppBlockCodec.serialize(block.get()).length;
            assertThat(size)
                    .as("finalized block %d must fit block.max-bytes", h)
                    .isLessThanOrEqualTo((int) blockMaxBytes);
        }
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
