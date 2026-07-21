package com.bloxbean.cardano.yano.appchain.integration.kafka;

import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.Arrays;
import java.util.Objects;

/**
 * One bounded application header; {@code yano-*} names are executor-reserved.
 *
 * @param name the normalized application-header name
 * @param value the header value bytes; defensively copied
 */
public record KafkaHeader(String name, byte[] value) implements Comparable<KafkaHeader> {
    /** Maximum normalized header-name size in bytes. */
    public static final int MAX_NAME_BYTES = 32;
    /** Maximum header-value size in bytes. */
    public static final int MAX_VALUE_BYTES = 256;

    /** Validates the name and defensively snapshots the value. */
    public KafkaHeader {
        name = ContractValidation.headerName(name);
        if (name.length() > MAX_NAME_BYTES) {
            throw com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor.malformed();
        }
        value = ContractValidation.bytes(value, 0, MAX_VALUE_BYTES);
    }

    /**
     * Returns a defensive copy of the header value.
     *
     * @return the header value bytes
     */
    @Override
    public byte[] value() {
        return value.clone();
    }

    /** Orders headers by their already-normalized names. */
    @Override
    public int compareTo(KafkaHeader other) {
        return name.compareTo(other.name);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof KafkaHeader header
                && name.equals(header.name)
                && Arrays.equals(value, header.value);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(name) + Arrays.hashCode(value);
    }
}
