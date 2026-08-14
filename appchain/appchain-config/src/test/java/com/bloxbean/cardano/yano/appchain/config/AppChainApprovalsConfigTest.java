package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainApprovalsConfigTest {

    @Test
    void parsesCompleteGenericEffectConfiguration() {
        AppChainApprovalsConfig config = AppChainApprovalsConfig.fromSettings(Map.of(
                "effects.enabled", "true",
                "effects.max-expiry-blocks", "500",
                "effects.result-window-blocks", "400",
                AppChainApprovalsConfig.ENABLED, "true",
                AppChainApprovalsConfig.TYPE, "com.acme.erp.create-order",
                AppChainApprovalsConfig.GATE, "zk-settled",
                AppChainApprovalsConfig.EXPIRY_BLOCKS, "300",
                AppChainApprovalsConfig.ACTIVATION, "12"));

        assertThat(config.enabled()).isTrue();
        assertThat(config.type()).isEqualTo("com.acme.erp.create-order");
        assertThat(config.gate()).isEqualTo(FinalityGate.ZK_SETTLED);
        assertThat(config.expiryBlocks()).isEqualTo(300);
        assertThat(config.maxPayloadBytes())
                .isEqualTo(AppChainEffectsConfig.DEFAULT_MAX_PAYLOAD_BYTES);
    }

    @Test
    void plainApprovalsRemainDisabledWithoutExtensionSettings() {
        assertThat(AppChainApprovalsConfig.fromSettings(Map.of()))
                .isEqualTo(AppChainApprovalsConfig.DISABLED);
        assertThat(AppChainApprovalsConfig.fromSettings(Map.of(
                AppChainApprovalsConfig.ENABLED, "false")))
                .isEqualTo(AppChainApprovalsConfig.DISABLED);
    }

    @Test
    void rejectsPartialDisabledOrFrameworkDisabledConfiguration() {
        assertThatThrownBy(() -> AppChainApprovalsConfig.fromSettings(Map.of(
                AppChainApprovalsConfig.TYPE, "webhook.post")))
                .hasMessageContaining(AppChainApprovalsConfig.ENABLED)
                .hasMessageContaining("must be true");

        assertThatThrownBy(() -> AppChainApprovalsConfig.fromSettings(Map.of(
                AppChainApprovalsConfig.ENABLED, "true",
                AppChainApprovalsConfig.TYPE, "webhook.post",
                AppChainApprovalsConfig.ACTIVATION, "1")))
                .hasMessageContaining("effects.enabled must be true");
    }

    @Test
    void rejectsMalformedTypeGateActivationAndExpiry() {
        Map<String, String> valid = validSettings();
        assertInvalid(valid, AppChainApprovalsConfig.ENABLED, "yes", "must be true or false");
        assertInvalid(valid, AppChainApprovalsConfig.TYPE, " ", "is required");
        assertInvalid(valid, AppChainApprovalsConfig.TYPE, "~reserved", "must not start with '~'");
        assertInvalid(valid, AppChainApprovalsConfig.GATE, "fast", "must be chain-default");
        assertInvalid(valid, AppChainApprovalsConfig.ACTIVATION, "0", "positive block height");
        assertInvalid(valid, AppChainApprovalsConfig.EXPIRY_BLOCKS, "-1", "decimal integer");
        assertInvalid(valid, AppChainApprovalsConfig.EXPIRY_BLOCKS, "101", "must not exceed");
        assertInvalid(valid, "effects.max-payload-bytes", "0", "positive integer");
        assertInvalid(valid, "effects.max-payload-bytes", "16777217", "must be <=");
    }

    @Test
    void rejectsUnknownAndRemovedPaymentSettings() {
        assertThatThrownBy(() -> AppChainApprovalsConfig.fromSettings(Map.of(
                "machines.approvals.on-approved-effect.typo", "true")))
                .hasMessageContaining("Unknown generic approvals effect setting");

        assertThatThrownBy(() -> AppChainApprovalsConfig.fromSettings(Map.of(
                "machines.approvals.payments", "true")))
                .hasMessageContaining("Unsupported pre-release approvals setting")
                .hasMessageContaining(AppChainApprovalsConfig.PREFIX);
    }

    private static Map<String, String> validSettings() {
        return Map.of(
                "effects.enabled", "true",
                "effects.max-expiry-blocks", "100",
                "effects.result-window-blocks", "100",
                AppChainApprovalsConfig.ENABLED, "true",
                AppChainApprovalsConfig.TYPE, "webhook.post",
                AppChainApprovalsConfig.GATE, "app-final",
                AppChainApprovalsConfig.EXPIRY_BLOCKS, "10",
                AppChainApprovalsConfig.ACTIVATION, "1");
    }

    private static void assertInvalid(Map<String, String> valid, String key,
                                      String value, String message) {
        Map<String, String> settings = new HashMap<>(valid);
        settings.put(key, value);
        assertThatThrownBy(() -> AppChainApprovalsConfig.fromSettings(settings))
                .hasMessageContaining(message);
    }
}
