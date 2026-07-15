package com.bloxbean.cardano.yano.appchain.integration.kafka;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorLimits;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.Arrays;
import java.util.List;

/**
 * Compact authenticated Kafka acknowledgement: destination, partition, offset.
 *
 * @param destinationFingerprint the 32-byte resolved-destination commitment
 * @param partition the acknowledged Kafka partition
 * @param offset the acknowledged Kafka offset
 */
public record KafkaPublishReceiptV1(byte[] destinationFingerprint,
                                    int partition,
                                    long offset) {
    /** Validates the acknowledgement and defensively snapshots the commitment. */
    public KafkaPublishReceiptV1 {
        destinationFingerprint = ContractValidation.hash32(destinationFingerprint);
        ContractValidation.bounded(partition, 0, Integer.MAX_VALUE);
        ContractValidation.bounded(offset, 0, Long.MAX_VALUE);
    }

    /**
     * Creates a receipt from a typed destination commitment.
     *
     * @param fingerprint the resolved-destination commitment
     * @param partition the acknowledged Kafka partition
     * @param offset the acknowledged Kafka offset
     */
    public KafkaPublishReceiptV1(ConnectorTargetFingerprint fingerprint, int partition, long offset) {
        this(fingerprint.bytes(), partition, offset);
    }

    /**
     * Returns a defensive copy of the destination commitment.
     *
     * @return the 32-byte destination commitment
     */
    @Override
    public byte[] destinationFingerprint() {
        return destinationFingerprint.clone();
    }

    /**
     * Encodes this receipt as strict canonical CBOR.
     *
     * @return a new canonical encoding
     */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(1));
        root.add(new ByteString(destinationFingerprint));
        root.add(new UnsignedInteger(partition));
        root.add(new UnsignedInteger(offset));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical Kafka acknowledgement.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated receipt
     */
    public static KafkaPublishReceiptV1 decode(byte[] bytes) {
        Array root = CanonicalCbor.decodeArray(bytes, ConnectorLimits.MAX_EXTERNAL_REF_BYTES, 4);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        return new KafkaPublishReceiptV1(
                CanonicalCbor.bytes(items.get(1)),
                CanonicalCbor.uintInt(items.get(2)),
                CanonicalCbor.uint(items.get(3)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof KafkaPublishReceiptV1 receipt
                && partition == receipt.partition
                && offset == receipt.offset
                && Arrays.equals(destinationFingerprint, receipt.destinationFingerprint);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(destinationFingerprint);
        result = 31 * result + Integer.hashCode(partition);
        return 31 * result + Long.hashCode(offset);
    }
}
