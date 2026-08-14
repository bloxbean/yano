package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class AppChainMpfPruningSchedulerTest {
    @Test
    void optInSchedulerPrunesOnItsOwnLaneAndReportsStatus(@TempDir Path directory)
            throws Exception {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) 0x31);
        String publicKey = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        AppChainConfig config = AppChainConfig.builder("scheduled-pruning")
                .signingKeyHex(HexUtil.encodeHexString(seed))
                .memberKeysHex(Set.of(publicKey))
                .proposerKeyHex(publicKey)
                .threshold(1)
                .blockIntervalMs(100)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .pluginSettings(Map.of(
                        StateProofPruningSettings.ENABLED, "true",
                        StateProofPruningSettings.RETAIN_HEIGHTS, "2",
                        StateProofPruningSettings.INTERVAL_SECONDS, "1"))
                .build();

        try (AppChainSubsystem node = new AppChainSubsystem(config, 42, null, null,
                directory.resolve("appchain").toString(), null,
                LoggerFactory.getLogger("scheduled-pruning-test"))) {
            node.start();
            for (int index = 0; index < 4; index++) {
                node.submit("history", ("message-" + index)
                        .getBytes(StandardCharsets.UTF_8));
                int expectedHeight = index + 1;
                await(() -> node.tipHeight() >= expectedHeight);
            }
            await(() -> node.oldestProvableHeight() >= 3);
            await(() -> pruningLong(node, "lastCompletedAt") > 0);

            Map<?, ?> commitment = (Map<?, ?>) node.status().get("stateCommitment");
            Map<?, ?> pruning = (Map<?, ?>) commitment.get("proofPruning");
            assertThat(pruning.get("enabled")).isEqualTo(true);
            assertThat(pruning.get("degraded")).isEqualTo(false);
            assertThat(((Number) pruning.get("lastCompletedAt")).longValue()).isPositive();
            assertThat(((Number) pruning.get("lastRetainFrom")).longValue())
                    .isGreaterThanOrEqualTo(3);
        }
    }

    private static void await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static long pruningLong(AppChainSubsystem node, String key) {
        Map<?, ?> commitment = (Map<?, ?>) node.status().get("stateCommitment");
        Map<?, ?> pruning = (Map<?, ?>) commitment.get("proofPruning");
        return ((Number) pruning.get(key)).longValue();
    }
}
