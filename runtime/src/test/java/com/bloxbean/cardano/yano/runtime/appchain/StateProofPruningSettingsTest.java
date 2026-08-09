package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateProofPruningSettingsTest {
    @Test
    void defaultsToDisabledAndCalculatesAContiguousHorizon() {
        StateProofPruningSettings defaults = StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF, Map.of()));
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.retainHeights()).isEqualTo(10_000);
        assertThat(defaults.retainFrom(8_000)).isEqualTo(1);

        StateProofPruningSettings enabled = StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF, Map.of(
                        StateProofPruningSettings.ENABLED, "true",
                        StateProofPruningSettings.RETAIN_HEIGHTS, "3",
                        StateProofPruningSettings.INTERVAL_SECONDS, "7")));
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.retainFrom(8)).isEqualTo(6);
        assertThat(enabled.intervalSeconds()).isEqualTo(7);
    }

    @Test
    void enabledPruningRejectsJmtAndNonPositiveBounds() {
        assertThatThrownBy(() -> StateProofPruningSettings.from(config(
                StateCommitmentProfiles.CLASSIC_JMT,
                Map.of(StateProofPruningSettings.ENABLED, "true"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only by mpf");
        assertThatThrownBy(() -> StateProofPruningSettings.from(config(
                StateCommitmentProfiles.MPF,
                Map.of(StateProofPruningSettings.RETAIN_HEIGHTS, "0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StateProofPruningSettings.RETAIN_HEIGHTS);
    }

    private static AppChainConfig config(
            com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfile profile,
            Map<String, String> settings) {
        return AppChainConfig.builder("pruning-settings")
                .signingKeyHex("")
                .stateCommitmentIdentity(StateCommitmentIdentity.explicit(
                        profile, new byte[32]))
                .pluginSettings(settings)
                .build();
    }
}
