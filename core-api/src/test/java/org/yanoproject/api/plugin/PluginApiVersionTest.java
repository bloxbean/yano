package org.yanoproject.api.plugin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginApiVersionTest {
    /** Level 12 adds RuleFact and the TransitionKernel rule-fact accessors (ADR-031.3). */
    @Test
    void publishesMajorThreeLevelTwelve() {
        assertThat(PluginApiVersion.CURRENT_MAJOR).isEqualTo(3);
        assertThat(PluginApiVersion.CURRENT_LEVEL).isEqualTo(12);
    }
}
