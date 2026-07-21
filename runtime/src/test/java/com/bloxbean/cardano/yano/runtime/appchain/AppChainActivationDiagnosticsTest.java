package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/** ADR-010.1 startup/status diagnostics for consensus-affecting activations. */
class AppChainActivationDiagnosticsTest {

    @Test
    void statusExposesActivationsWhenEffectsAreDisabled() {
        AppChainSubsystem node = node(Map.of(
                "effects.enabled", "false",
                "machines.approvals.activations.on-approved-effect", "42",
                "effects.activations.record-v2", "100",
                "machines.approvals.on-approved-effect.type", "ignored"));

        assertThat(node.status().get("effects")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, String> activations = (Map<String, String>) node.status().get("activations");
        assertThat(activations).containsExactly(
                entry("effects.activations.record-v2", "100"),
                entry("machines.approvals.activations.on-approved-effect", "42"));
    }

    @Test
    void startupLogsEveryActivationInStableOrder() {
        Logger logger = mock(Logger.class);
        AppChainSubsystem node = node(Map.of(
                "machines.approvals.activations.on-approved-effect", "42",
                "effects.activations.record-v2", "100"), logger);
        try {
            node.start();
        } finally {
            node.stop();
        }

        InOrder ordered = inOrder(logger);
        ordered.verify(logger).info("App-chain '{}' transition activation {}={}",
                "activation-diagnostics", "effects.activations.record-v2", 100L);
        ordered.verify(logger).info("App-chain '{}' transition activation {}={}",
                "activation-diagnostics",
                "machines.approvals.activations.on-approved-effect", 42L);
    }

    @Test
    void startupRejectsMalformedActivationHeight() {
        AppChainSubsystem node = node(Map.of(
                "machines.approvals.activations.on-approved-effect", "TBD"));

        assertThatThrownBy(node::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transition activation")
                .hasMessageContaining("must be a positive block height");
    }

    private static AppChainSubsystem node(Map<String, String> settings) {
        return node(settings, LoggerFactory.getLogger(AppChainActivationDiagnosticsTest.class));
    }

    private static AppChainSubsystem node(Map<String, String> settings, Logger logger) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 91);
        String publicKey = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(key));
        AppChainConfig config = AppChainConfig.builder("activation-diagnostics")
                .signingKeyHex(HexUtil.encodeHexString(key))
                .memberKeysHex(Set.of(publicKey))
                .pluginSettings(settings)
                .build();
        return new AppChainSubsystem(config, 42, null, null, null, null, logger);
    }
}
