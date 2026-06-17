package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yano.api.config.YanoConfig;

import java.util.Objects;

/**
 * Producer strategy selected from runtime configuration before construction.
 */
public record ProducerStartupPlan(ProducerMode mode, boolean deferredUntilGenesisShift) {
    public ProducerStartupPlan {
        Objects.requireNonNull(mode, "mode");
    }

    public boolean startsImmediately() {
        return !deferredUntilGenesisShift;
    }

    public static ProducerStartupPlan from(YanoConfig config) {
        Objects.requireNonNull(config, "config");
        if (!config.isEnableBlockProducer()) {
            throw new IllegalArgumentException("Producer startup plan requires block producer mode");
        }
        if (config.isSlotLeaderMode()) {
            return new ProducerStartupPlan(ProducerMode.SLOT_LEADER, false);
        }
        if (config.isPastTimeTravelMode()) {
            if (config.isPastTimeTravelSlotLeaderMode()) {
                return new ProducerStartupPlan(ProducerMode.SLOT_LEADER_TIME_TRAVEL, true);
            }
            return new ProducerStartupPlan(ProducerMode.DEVNET_TIME_TRAVEL, true);
        }
        return new ProducerStartupPlan(ProducerMode.DEVNET, false);
    }
}
