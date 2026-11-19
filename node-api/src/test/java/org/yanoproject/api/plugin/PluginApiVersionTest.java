package org.yanoproject.api.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginApiVersionTest {
    /**
     * Level 12 adds RuleFact and the TransitionKernel rule-fact accessors (ADR-031.3); level 13 adds the
     * topic registry API, payload views and gateway topic reads (ADR app-layer/039); level 14 adds the open
     * contribution-kind SPI ({@code org.yanoproject.api.plugin.spi}) and {@code PluginOperationsRecorder}
     * (ADR-064).
     */
    @Test
    void publishesMajorThreeLevelFourteen() {
        assertThat(PluginApiVersion.CURRENT_MAJOR).isEqualTo(3);
        assertThat(PluginApiVersion.CURRENT_LEVEL).isEqualTo(14);
    }
}
