package com.bloxbean.cardano.yano.runtime.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeNodeEffectConfigTest {

    @Test
    void appChainPluginSettingsIncludesEffectsNamespace() {
        Map<String, String> settings = RuntimeNode.appChainPluginSettings(prefix -> switch (prefix) {
            case "effects." -> Map.of(
                    "effects.enabled", "true",
                    "effects.executor.enabled", "true");
            case "transport." -> Map.of("transport.shared.enabled", "true");
            default -> Map.of();
        });

        assertEquals("true", settings.get("effects.enabled"));
        assertEquals("true", settings.get("effects.executor.enabled"));
        assertEquals("true", settings.get("transport.shared.enabled"));
        assertFalse(settings.containsKey("enabled"));
    }
}
