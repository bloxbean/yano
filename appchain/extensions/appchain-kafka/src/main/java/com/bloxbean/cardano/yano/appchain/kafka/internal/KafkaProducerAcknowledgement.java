package com.bloxbean.cardano.yano.appchain.kafka.internal;

/**
 * Bounded allowlisted Kafka acknowledgement metadata.
 *
 * @param partition the non-negative acknowledged partition
 * @param offset the non-negative acknowledged offset
 * @param serializedKeySize the non-negative serialized key size
 * @param serializedValueSize the non-negative serialized value size
 * @hidden internal connector boundary
 */
public record KafkaProducerAcknowledgement(int partition,
                                           long offset,
                                           int serializedKeySize,
                                           int serializedValueSize) {
    private static final int MAX_SERIALIZED_KEY_BYTES = 256;
    private static final int MAX_SERIALIZED_VALUE_BYTES = 8_192;

    /** Validates every acknowledgement field before it reaches a receipt/detail codec. */
    public KafkaProducerAcknowledgement {
        if (partition < 0 || offset < 0
                || serializedKeySize < 0 || serializedKeySize > MAX_SERIALIZED_KEY_BYTES
                || serializedValueSize < 0 || serializedValueSize > MAX_SERIALIZED_VALUE_BYTES) {
            throw new IllegalArgumentException("invalid Kafka acknowledgement metadata");
        }
    }
}
