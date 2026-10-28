package org.yanoproject.api.appchain.topics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR app-layer/039 D4: the genesis seed is bounded like a governed configuration. */
class TopicChainSettingsTest {
    private static final String KEY = "11".repeat(32);

    @Test
    void boundsTheRegistrarThresholdAndTheActivationLag() {
        assertThat(TopicChainSettings.genesisSeed(Map.of(TopicChainSettings.MODE, "enabled",
                TopicChainSettings.REGISTRAR_KEYS, KEY)).orElseThrow().registrars().threshold()).isEqualTo(1);
        // 2^32 + 1 used to narrow to a threshold of 1
        assertThatThrownBy(() -> TopicChainSettings.genesisSeed(Map.of(TopicChainSettings.MODE, "enabled",
                TopicChainSettings.REGISTRAR_KEYS, KEY, TopicChainSettings.REGISTRAR_THRESHOLD, "4294967297")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicChainSettings.genesisSeed(Map.of(TopicChainSettings.MODE, "enabled",
                TopicChainSettings.ACTIVATION_LAG, String.valueOf(TopicWire.Settings.MAX_ACTIVATION_LAG + 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
