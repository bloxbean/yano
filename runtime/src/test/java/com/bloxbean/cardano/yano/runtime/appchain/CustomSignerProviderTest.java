package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProviderFactory;
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
 * ADR-006 E4.3: a member's signing key resolves through a custom
 * SignerProviderFactory (ServiceLoader) instead of a raw seed — the node never
 * holds the key material directly. Registered via
 * META-INF/services (see test resources).
 */
@Timeout(60)
class CustomSignerProviderTest {

    private static final Logger log = LoggerFactory.getLogger(CustomSignerProviderTest.class);
    private static final byte[] SEED = seed(120);

    /** Test factory for the "test-seed:" scheme — signs with the referenced seed. */
    public static final class TestSeedSignerFactory implements SignerProviderFactory {
        @Override
        public String scheme() {
            return "test-seed";
        }

        @Override
        public SignerProvider create(String keyReference) {
            byte[] key = HexUtil.decodeHexString(keyReference);
            byte[] pub = KeyGenUtil.getPublicKeyFromPrivateKey(key);
            SigningProvider crypto = CryptoConfiguration.INSTANCE.getSigningProvider();
            return new SignerProvider() {
                @Override
                public byte[] sign(byte[] message) {
                    return crypto.sign(message, key);
                }

                @Override
                public byte[] publicKey() {
                    return pub;
                }
            };
        }
    }

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void memberKeyResolvedViaProviderScheme_signsAndFinalizes() throws Exception {
        String pub = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(SEED));
        // signing-key is a scheme reference, NOT a raw seed
        AppChainConfig config = AppChainConfig.builder("signer-chain")
                .signingKeyHex("test-seed:" + HexUtil.encodeHexString(SEED))
                .memberKeysHex(Set.of(pub))
                .proposerKeyHex(pub)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        String id = node.submit("t", "signed via provider".getBytes(StandardCharsets.UTF_8));
        awaitTrue("finalized", () -> node.messageHeight(HexUtil.decodeHexString(id)).isPresent());

        // Message accepted means its signature (produced by the provider) verified
        assertThat(node.recentMessages(10)).anyMatch(m -> m.messageIdHex().equals(id));
        assertThat(node.status().get("memberKey")).isEqualTo(pub);
    }

    @Test
    void unknownScheme_failsFast() {
        String pub = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(SEED));
        AppChainConfig config = AppChainConfig.builder("bad-signer-chain")
                .signingKeyHex("no-such-scheme:whatever")
                .memberKeysHex(Set.of(pub))
                .proposerKeyHex(pub)
                .threshold(1)
                .build();
        assertThatThrownBy(() -> new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger2").toString(), null, log))
                .isInstanceOf(PluginActivationException.class)
                .hasMessageContaining("no-such-scheme")
                .hasMessageContaining("test-seed");
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
