package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectsSettingsConsensusProfileTest {
    private static final String MEMBER = "11".repeat(32);

    @Test
    void disabledEffectsCanonicalizeIgnoredRawSettings() {
        AppChainConfig clean = config(Map.of());
        AppChainConsensusProfile cleanProfile = EffectsSettings.from(clean)
                .consensusProfile(clean);

        AppChainConfig noisy = config(Map.of(
                "effects.enabled", "false",
                "effects.max-per-block", "999999",
                "effects.max-payload-bytes", "999999",
                "effects.max-expiry-blocks", "999999",
                "effects.result-window-blocks", "999999",
                "effects.default-gate", "zk-settled",
                "effects.outcome-commitment", "per-block",
                "effects.result.signers", "22".repeat(32)));
        AppChainConsensusProfile noisyProfile = EffectsSettings.from(noisy)
                .consensusProfile(noisy);

        assertThat(noisyProfile).isEqualTo(cleanProfile);
    }

    @Test
    void duplicateAndNonmemberResultSignersFailBeforeProfilePublication() {
        String duplicate = MEMBER + "," + MEMBER.toUpperCase(java.util.Locale.ROOT);
        assertThatThrownBy(() -> EffectsSettings.fromSettings(Map.of(
                "effects.enabled", "true",
                "effects.result.signers", duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate key");

        Map<String, String> settings = new HashMap<>();
        settings.put("effects.enabled", "true");
        settings.put("effects.result.signers", "22".repeat(32));
        AppChainConfig config = config(settings);
        assertThatThrownBy(() -> EffectsSettings.from(config).consensusProfile(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonmember key");
    }

    private static AppChainConfig config(Map<String, String> settings) {
        return AppChainConfig.builder("profile-settings-test")
                .signingKeyHex("33".repeat(32))
                .memberKeysHex(Set.of(MEMBER))
                .proposerKeyHex(MEMBER)
                .maxBlockMessages(100)
                .pluginSettings(settings)
                .build();
    }
}
