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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5: a custom state machine is resolved by id through the
 * {@link com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider}
 * ServiceLoader SPI — the mechanism plugin jars use on a stock yano
 * distribution — and drives the sequenced ledger.
 */
@Timeout(60)
class AppChainPluginStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainPluginStateMachineTest.class);
    private static final byte[] KEY_A = seed(41);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop();
        }
    }

    @Test
    void customStateMachine_loadedViaServiceLoader_andApplied() throws Exception {
        String pubA = pubHex(KEY_A);
        AppChainConfig config = new AppChainConfig(
                "plugin-chain",
                HexUtil.encodeHexString(KEY_A),
                Set.of(pubA),
                List.of(),
                65536, 3600, 600,
                pubA,           // single-member chain: self-proposing
                1,              // threshold 1 (self-vote)
                300,
                100,
                TestKvStateMachineProvider.ID,   // NOT a built-in — resolved via ServiceLoader
                null,
                null, 0);

        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        node.submit("kv", "color=blue".getBytes(StandardCharsets.UTF_8));

        long deadline = System.currentTimeMillis() + 20_000;
        while (node.tipHeight() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(node.tipHeight()).isGreaterThanOrEqualTo(1);

        // The custom machine interpreted the opaque body and wrote key -> value
        assertThat(node.stateValue("color".getBytes(StandardCharsets.UTF_8)))
                .contains("blue".getBytes(StandardCharsets.UTF_8));
        assertThat(node.stateProof("color".getBytes(StandardCharsets.UTF_8))).isPresent();

        // Unknown ids fail fast with the available list
        AppChainConfig badConfig = new AppChainConfig(
                "bad-chain", HexUtil.encodeHexString(KEY_A), Set.of(pubA), List.of(),
                65536, 3600, 600, pubA, 1, 300, 100, "no-such-machine", null, null, 0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new AppChainSubsystem(badConfig, 42, null, null,
                                tempDir.resolve("ledger2").toString(), null, log))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-machine")
                .hasMessageContaining(TestKvStateMachineProvider.ID);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }

    private static String pubHex(byte[] privateKey) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(privateKey));
    }
}
