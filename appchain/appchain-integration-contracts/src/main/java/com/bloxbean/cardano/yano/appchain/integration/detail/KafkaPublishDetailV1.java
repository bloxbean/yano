package com.bloxbean.cardano.yano.appchain.integration.detail;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.Arrays;
import java.util.List;

/**
 * Allowlisted stable Kafka acknowledgement detail.
 *
 * @param destinationFingerprint the 32-byte resolved-destination commitment
 * @param partition the acknowledged Kafka partition
 * @param offset the acknowledged Kafka offset
 * @param serializedKeySize the serialized record-key size in bytes
 * @param serializedValueSize the serialized record-value size in bytes
 */
public record KafkaPublishDetailV1(byte[] destinationFingerprint,
                                   int partition,
                                   long offset,
                                   int serializedKeySize,
                                   int serializedValueSize) implements ConnectorDetailData {
    /** Validates stable acknowledgement fields and snapshots the fingerprint. */
    public KafkaPublishDetailV1 {
        destinationFingerprint = ContractValidation.hash32(destinationFingerprint);
        ContractValidation.bounded(partition, 0, Integer.MAX_VALUE);
        ContractValidation.bounded(offset, 0, Long.MAX_VALUE);
        ContractValidation.bounded(serializedKeySize, 0, 16_384);
        ContractValidation.bounded(serializedValueSize, 0, 16_384);
    }

    /**
     * Returns a defensive copy of the destination commitment.
     *
     * @return the 32-byte destination commitment
     */
    @Override public byte[] destinationFingerprint() { return destinationFingerprint.clone(); }

    /** {@inheritDoc} */
    @Override public ConnectorAction action() { return ConnectorAction.KAFKA_PUBLISH; }

    @Override
    public byte[] encode() {
        Array data = new Array();
        data.add(new UnsignedInteger(1));
        data.add(new ByteString(destinationFingerprint));
        data.add(new UnsignedInteger(partition));
        data.add(new UnsignedInteger(offset));
        data.add(new UnsignedInteger(serializedKeySize));
        data.add(new UnsignedInteger(serializedValueSize));
        return CanonicalCbor.encode(data);
    }

    /**
     * Decodes and validates canonical Kafka detail data.
     *
     * @param bytes the canonical detail encoding
     * @return the validated detail data
     */
    public static KafkaPublishDetailV1 decode(byte[] bytes) {
        Array data = CanonicalCbor.decodeArray(bytes, 512, 6);
        List<DataItem> items = CanonicalCbor.items(data);
        CanonicalCbor.requireVersion(items.get(0));
        return new KafkaPublishDetailV1(CanonicalCbor.bytes(items.get(1)),
                CanonicalCbor.uintInt(items.get(2)), CanonicalCbor.uint(items.get(3)),
                CanonicalCbor.uintInt(items.get(4)), CanonicalCbor.uintInt(items.get(5)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof KafkaPublishDetailV1 detail
                && partition == detail.partition
                && offset == detail.offset
                && serializedKeySize == detail.serializedKeySize
                && serializedValueSize == detail.serializedValueSize
                && Arrays.equals(destinationFingerprint, detail.destinationFingerprint);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(destinationFingerprint);
        result = 31 * result + Integer.hashCode(partition);
        result = 31 * result + Long.hashCode(offset);
        result = 31 * result + Integer.hashCode(serializedKeySize);
        return 31 * result + Integer.hashCode(serializedValueSize);
    }
}
