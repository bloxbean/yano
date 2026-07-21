package com.bloxbean.cardano.yano.appchain.integration.kafka;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import com.bloxbean.cardano.yano.appchain.integration.internal.ContractValidation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical v1 payload for {@code kafka.publish}.
 *
 * @param target the configured Kafka target alias
 * @param topic the logical topic alias resolved within the target
 * @param key the record key bytes; defensively copied
 * @param contentType the media type of {@code body}
 * @param body the record body bytes; defensively copied
 * @param headers bounded application headers, normalized by name
 */
public record KafkaPublishCommandV1(String target,
                                    String topic,
                                    byte[] key,
                                    String contentType,
                                    byte[] body,
                                    List<KafkaHeader> headers) {
    /** Maximum canonical command size in bytes. */
    public static final int MAX_ENCODED_BYTES = 12_288;
    /** Maximum record-key size in bytes. */
    public static final int MAX_KEY_BYTES = 256;
    /** Maximum record-body size in bytes. */
    public static final int MAX_BODY_BYTES = 8_192;
    /** Maximum number of application headers. */
    public static final int MAX_HEADERS = 16;
    /** Maximum aggregate application-header size in bytes. */
    public static final int MAX_HEADER_BYTES = 2_048;

    /** Validates fields, snapshots byte arrays, and canonicalizes header ordering. */
    public KafkaPublishCommandV1 {
        target = ContractValidation.alias(target, "target");
        topic = ContractValidation.alias(topic, "topic");
        key = ContractValidation.bytes(key, 0, MAX_KEY_BYTES);
        contentType = ContractValidation.contentType(contentType);
        body = ContractValidation.bytes(body, 0, MAX_BODY_BYTES);
        if (headers == null || headers.size() > MAX_HEADERS) {
            throw CanonicalCbor.malformed();
        }
        List<KafkaHeader> normalized = new ArrayList<>(headers);
        if (normalized.stream().anyMatch(Objects::isNull)) {
            throw CanonicalCbor.malformed();
        }
        normalized.sort(KafkaHeader::compareTo);
        Set<String> names = new HashSet<>();
        int totalBytes = 0;
        for (KafkaHeader header : normalized) {
            if (header == null || !names.add(header.name())) {
                throw CanonicalCbor.malformed();
            }
            totalBytes += header.name().length() + header.value().length;
            if (totalBytes > MAX_HEADER_BYTES) {
                throw CanonicalCbor.malformed();
            }
        }
        headers = List.copyOf(normalized);
    }

    /**
     * Returns a defensive copy of the record key.
     *
     * @return the record-key bytes
     */
    @Override
    public byte[] key() {
        return key.clone();
    }

    /**
     * Returns a defensive copy of the record body.
     *
     * @return the record-body bytes
     */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /**
     * Encodes this command as strict canonical CBOR.
     *
     * @return a new canonical encoding
     */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(1));
        root.add(new UnicodeString(target));
        root.add(new UnicodeString(topic));
        root.add(new ByteString(key));
        root.add(new UnicodeString(contentType));
        root.add(new ByteString(body));
        Array encodedHeaders = new Array();
        for (KafkaHeader header : headers) {
            Array pair = new Array();
            pair.add(new UnicodeString(header.name()));
            pair.add(new ByteString(header.value()));
            encodedHeaders.add(pair);
        }
        root.add(encodedHeaders);
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, MAX_ENCODED_BYTES);
        return encoded;
    }

    /**
     * Decodes and validates a canonical Kafka publish command.
     *
     * @param bytes the bounded canonical encoding
     * @return the validated command
     */
    public static KafkaPublishCommandV1 decode(byte[] bytes) {
        byte[] input = CanonicalCbor.boundedSnapshot(bytes, MAX_ENCODED_BYTES);
        Array root = CanonicalCbor.decodeArray(input, MAX_ENCODED_BYTES, 7);
        List<DataItem> items = CanonicalCbor.items(root);
        CanonicalCbor.requireVersion(items.get(0));
        Array headerArray = CanonicalCbor.array(items.get(6));
        if (headerArray.getDataItems().size() > MAX_HEADERS) {
            throw CanonicalCbor.malformed();
        }
        List<KafkaHeader> headers = new ArrayList<>();
        for (DataItem item : headerArray.getDataItems()) {
            List<DataItem> pair = CanonicalCbor.items(CanonicalCbor.array(item, 2));
            headers.add(new KafkaHeader(CanonicalCbor.text(pair.get(0)),
                    CanonicalCbor.bytes(pair.get(1))));
        }
        KafkaPublishCommandV1 command = new KafkaPublishCommandV1(
                CanonicalCbor.text(items.get(1)),
                CanonicalCbor.text(items.get(2)),
                CanonicalCbor.bytes(items.get(3)),
                CanonicalCbor.text(items.get(4)),
                CanonicalCbor.bytes(items.get(5)),
                headers);
        if (!java.util.Arrays.equals(input, command.encode())) {
            throw CanonicalCbor.malformed();
        }
        return command;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof KafkaPublishCommandV1 command
                && target.equals(command.target)
                && topic.equals(command.topic)
                && Arrays.equals(key, command.key)
                && contentType.equals(command.contentType)
                && Arrays.equals(body, command.body)
                && headers.equals(command.headers);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(target, topic, contentType, headers);
        result = 31 * result + Arrays.hashCode(key);
        return 31 * result + Arrays.hashCode(body);
    }
}
