package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.crypto.BodyCipher;
import com.bloxbean.cardano.yano.appchain.client.GroupCipher;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
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
 * ADR-006 E4.2: encrypted message bodies. The client cipher (SDK GroupCipher)
 * and node cipher (core-api BodyCipher) interoperate; an encrypted body flows
 * through a real chain blob-first — finalized and provable over ciphertext —
 * and decrypts back to plaintext.
 */
@Timeout(60)
class EncryptedBodyTest {

    private static final Logger log = LoggerFactory.getLogger(EncryptedBodyTest.class);
    private static final byte[] KEY_A = seed(130);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void clientAndNodeCiphers_interoperate() {
        byte[] groupKey = BodyCipher.generateKey();
        byte[] plaintext = "settlement instruction #42".getBytes(StandardCharsets.UTF_8);

        // encrypt on the client, decrypt on the node
        byte[] fromClient = GroupCipher.encrypt(groupKey, "settlement", plaintext);
        assertThat(BodyCipher.isEncrypted(fromClient)).isTrue();
        assertThat(BodyCipher.decrypt(groupKey, "settlement", fromClient)).isEqualTo(plaintext);

        // encrypt on the node, decrypt on the client
        byte[] fromNode = BodyCipher.encrypt(groupKey, "settlement", plaintext);
        assertThat(GroupCipher.decrypt(groupKey, "settlement", fromNode)).isEqualTo(plaintext);

        // wrong topic (AAD) or wrong key fails closed
        assertThatThrownBy(() -> BodyCipher.decrypt(groupKey, "other-topic", fromClient))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BodyCipher.decrypt(BodyCipher.generateKey(), "settlement", fromClient))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void encryptedBody_flowsThroughChain_provableAndDecryptable() throws Exception {
        byte[] groupKey = BodyCipher.generateKey();
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("enc-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        byte[] plaintext = "confidential: transfer 100 to bank B".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = GroupCipher.encrypt(groupKey, "settlement", plaintext);

        // The chain only ever sees ciphertext
        String id = node.submit("settlement", ciphertext);
        awaitTrue("finalized", () -> node.messageHeight(HexUtil.decodeHexString(id)).isPresent());

        // Ordering/proof work over the opaque ciphertext
        assertThat(node.stateProof(HexUtil.decodeHexString(id))).isPresent();

        // The stored body is ciphertext; a holder of the group key decrypts it
        byte[] storedBody = node.recentMessages(10).stream()
                .filter(m -> m.messageIdHex().equals(id))
                .findFirst().orElseThrow().body();
        assertThat(BodyCipher.isEncrypted(storedBody)).isTrue();
        assertThat(new String(storedBody, StandardCharsets.UTF_8)).doesNotContain("transfer");
        assertThat(GroupCipher.decrypt(groupKey, "settlement", storedBody)).isEqualTo(plaintext);
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
