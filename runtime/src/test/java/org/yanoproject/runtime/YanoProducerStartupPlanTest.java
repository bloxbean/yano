package org.yanoproject.runtime;

import org.yanoproject.runtime.internal.RuntimeNode;

import org.yanoproject.api.config.YanoConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoProducerStartupPlanTest {
    @Test
    void shiftGenesisRejectsImmediateSlotLeaderStartupPlan() {
        YanoConfig config = YanoConfig.builder()
                .enableBlockProducer(true)
                .enableServer(true)
                .devMode(true)
                .slotLeaderMode(true)
                .pastTimeTravelMode(true)
                .protocolMagic(42)
                .build();
        RuntimeNode yano = new RuntimeNode(config);

        try {
            assertThatThrownBy(() -> yano.devnetRuntime().orElseThrow()
                    .producerExtensions()
                    .shiftGenesisAndStartProducer(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires a deferred past-time-travel producer plan");
        } finally {
            yano.close();
        }
    }
}
