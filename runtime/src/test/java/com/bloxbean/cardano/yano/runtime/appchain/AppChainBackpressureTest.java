package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.PoolFullException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR app-layer/008.1 I1.1: a full pending pool rejects local submissions with
 * {@link PoolFullException} (never a silent "accepted" id) and counts dropped
 * inbound gossip by reason in {@code status().drops}.
 */
@Timeout(60)
class AppChainBackpressureTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainBackpressureTest.class);
    private static final byte[] KEY_A = seed(91);

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
    void fullPool_localSubmitThrows_inboundDropCounted() {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("bp-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                // Long interval so the proposer does not drain the pool mid-test
                .blockIntervalMs(60_000)
                .maxBlockMessages(1)
                .poolMaxMessages(1)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // First submit fills the capacity-1 pool
        String id1 = node.submit("t", "one".getBytes(StandardCharsets.UTF_8));
        assertThat(id1).isNotBlank();

        // Second submit must be rejected loudly — not an "accepted" id
        assertThatThrownBy(() -> node.submit("t", "two".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PoolFullException.class)
                .hasMessageContaining("pool is full");

        // Inbound gossip on a full pool is dropped and counted (best-effort path)
        long recordedBefore = node.recentMessages(100).size();
        node.onInboundMessages(List.of(inboundMessage("peer-msg")));
        assertThat(node.recentMessages(100)).hasSize((int) recordedBefore);

        Map<String, Object> status = node.status();
        assertThat(status.get("poolCapacity")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Long> drops = (Map<String, Long>) status.get("drops");
        // one local submit drop + one inbound drop
        assertThat(drops.get("pool_full")).isEqualTo(2L);
    }

    /** Hand-built envelope; route() does not re-verify auth (the agent does). */
    private static AppMessage inboundMessage(String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        byte[] sender = KeyGenUtil.getPublicKeyFromPrivateKey(seed(92));
        long expiresAt = System.currentTimeMillis() / 1000 + 600;
        byte[] messageId = AppMessage.computeMessageId("bp-chain", "t", sender, 1, expiresAt, payload);
        return AppMessage.builder()
                .messageId(messageId)
                .chainId("bp-chain")
                .topic("t")
                .sender(sender)
                .senderSeq(1)
                .expiresAt(expiresAt)
                .body(payload)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(new byte[64])
                .build();
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
