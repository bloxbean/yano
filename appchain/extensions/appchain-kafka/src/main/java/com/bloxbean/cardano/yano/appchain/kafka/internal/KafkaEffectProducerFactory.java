package com.bloxbean.cardano.yano.appchain.kafka.internal;

import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaEffectConfig;

/**
 * Injectable constructor for fresh per-target producer clients.
 *
 * @hidden internal connector boundary
 */
@FunctionalInterface
public interface KafkaEffectProducerFactory {
    /**
     * Creates a new producer owned by one executor.
     *
     * @param target the validated target configuration
     * @return a fresh producer client
     */
    KafkaEffectProducer open(KafkaEffectConfig.Target target);
}
