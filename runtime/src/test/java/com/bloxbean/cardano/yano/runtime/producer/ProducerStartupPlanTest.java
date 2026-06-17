package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProducerStartupPlanTest {
    @Test
    void normalDevnetStartsImmediately() {
        ProducerStartupPlan plan = ProducerStartupPlan.from(producerConfig().build());

        assertThat(plan.mode()).isEqualTo(ProducerMode.DEVNET);
        assertThat(plan.startsImmediately()).isTrue();
    }

    @Test
    void slotLeaderStartsImmediatelyAndTakesPrecedenceOverPastTimeTravelFlag() {
        ProducerStartupPlan plan = ProducerStartupPlan.from(producerConfig()
                .slotLeaderMode(true)
                .pastTimeTravelMode(true)
                .build());

        assertThat(plan.mode()).isEqualTo(ProducerMode.SLOT_LEADER);
        assertThat(plan.startsImmediately()).isTrue();
    }

    @Test
    void devnetTimeTravelDefersUntilGenesisShift() {
        ProducerStartupPlan plan = ProducerStartupPlan.from(producerConfig()
                .pastTimeTravelMode(true)
                .build());

        assertThat(plan.mode()).isEqualTo(ProducerMode.DEVNET_TIME_TRAVEL);
        assertThat(plan.deferredUntilGenesisShift()).isTrue();
    }

    @Test
    void slotLeaderTimeTravelDefersUntilGenesisShift() {
        ProducerStartupPlan plan = ProducerStartupPlan.from(producerConfig()
                .pastTimeTravelMode(true)
                .pastTimeTravelSlotLeaderMode(true)
                .build());

        assertThat(plan.mode()).isEqualTo(ProducerMode.SLOT_LEADER_TIME_TRAVEL);
        assertThat(plan.deferredUntilGenesisShift()).isTrue();
    }

    @Test
    void slotLeaderTimeTravelFlagWithoutPastTimeTravelPreservesNormalDevnetStartup() {
        ProducerStartupPlan plan = ProducerStartupPlan.from(producerConfig()
                .pastTimeTravelSlotLeaderMode(true)
                .build());

        assertThat(plan.mode()).isEqualTo(ProducerMode.DEVNET);
        assertThat(plan.startsImmediately()).isTrue();
    }

    @Test
    void requiresBlockProducerMode() {
        assertThatThrownBy(() -> ProducerStartupPlan.from(producerConfig()
                .enableBlockProducer(false)
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires block producer mode");
    }

    private YanoConfig.YanoConfigBuilder producerConfig() {
        return YanoConfig.builder()
                .enableBlockProducer(true)
                .enableServer(true)
                .devMode(true);
    }
}
