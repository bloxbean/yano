package com.bloxbean.cardano.yano.appchain.kafka.internal;

import java.util.Map;

/**
 * Injectable synchronous producer boundary owned by exactly one effect
 * executor. Implementations bound both send and close operations.
 *
 * @hidden internal connector boundary
 */
public interface KafkaEffectProducer extends AutoCloseable {
    /**
     * Publishes one bounded byte record and waits for its broker acknowledgement.
     * Kafka runtime exceptions are propagated unchanged for safe classification.
     *
     * @param physicalTopic the allowlisted physical topic from executor configuration
     * @param key the stable record key
     * @param value the bounded record body
     * @param headers unique bounded executor/application headers
     * @return bounded broker acknowledgement metadata
     * @throws InterruptedException when the calling thread is interrupted while waiting
     */
    KafkaProducerAcknowledgement publish(String physicalTopic,
                                         byte[] key,
                                         byte[] value,
                                         Map<String, byte[]> headers) throws InterruptedException;

    /** Closes the owned producer once, within its configured timeout. */
    @Override
    void close();
}
