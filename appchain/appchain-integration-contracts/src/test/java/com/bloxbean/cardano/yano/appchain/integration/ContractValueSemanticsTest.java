package com.bloxbean.cardano.yano.appchain.integration;

import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailDocumentV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailHash;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorAction;
import com.bloxbean.cardano.yano.appchain.integration.detail.IpfsPinDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.KafkaPublishDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectPutDetailV1;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinSubmittedRefV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaHeader;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectVersionFingerprint;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ContractValueSemanticsTest {
    private static final String RAW_CID =
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi";

    @Test
    void everyByteArrayRecordHasDeepValueSemantics() {
        List<ValuePair> pairs = valuePairs();
        Map<Object, Integer> indexed = new HashMap<>();
        HashSet<Object> values = new HashSet<>();

        for (int index = 0; index < pairs.size(); index++) {
            ValuePair pair = pairs.get(index);
            assertThat(pair.second()).isEqualTo(pair.first());
            assertThat(pair.second().hashCode()).isEqualTo(pair.first().hashCode());

            indexed.put(pair.first(), index);
            values.add(pair.first());
            assertThat(indexed).containsEntry(pair.second(), index);
            assertThat(values).contains(pair.second());
        }
    }

    @Test
    void arrayAccessorsAndConstructorInputsCannotMutateStoredValues() {
        byte[] input = bytes(1, 2, 3);
        KafkaHeader header = new KafkaHeader("trace-id", input);
        input[0] = 99;
        byte[] returned = header.value();
        returned[1] = 99;

        assertThat(header.value()).containsExactly(1, 2, 3);
        assertThat(header).isEqualTo(new KafkaHeader("trace-id", bytes(1, 2, 3)));
        assertThat(header).isNotEqualTo(new KafkaHeader("trace-id", bytes(1, 2, 4)));
    }

    @Test
    void nullKafkaHeaderUsesStableFailClosedClassification() {
        List<KafkaHeader> headers = new ArrayList<>();
        headers.add(null);

        assertInvalid(() -> new KafkaPublishCommandV1(
                "broker-v1", "events", new byte[0], "application/json", new byte[0], headers));
    }

    @Test
    void ipfsCommandAndDetailShareTheSameNarrowCidPolicy() {
        CanonicalCid allowed = CanonicalCid.fromText(RAW_CID);
        assertThat(allowed.bytes()).hasSize(36);
        assertThat(new IpfsPinCommandV1("kubo-v1", allowed, true, null).cid())
                .isEqualTo(allowed);
        assertThat(new IpfsPinDetailV1(hash(1), allowed, true, null).cid())
                .isEqualTo(allowed);

        CanonicalCid unsupported = CanonicalCid.fromBytes(unsupportedCodecCid());
        assertInvalid(() -> new IpfsPinCommandV1("kubo-v1", unsupported, true, null));
        assertInvalid(() -> new IpfsPinDetailV1(hash(1), unsupported, true, null));
    }

    @Test
    void objectRetentionModesHaveExactNullabilityAndStableCodes() {
        ObjectPutDetailV1 none = objectDetail(ObjectRetentionMode.NONE, null);
        ObjectPutDetailV1 governance = objectDetail(
                ObjectRetentionMode.GOVERNANCE, 1_900_000_000_000L);
        ObjectPutDetailV1 compliance = objectDetail(
                ObjectRetentionMode.COMPLIANCE, 1_900_000_000_000L);

        assertThat(ObjectPutDetailV1.decode(none.encode())).isEqualTo(none);
        assertThat(ObjectPutDetailV1.decode(governance.encode())).isEqualTo(governance);
        assertThat(ObjectPutDetailV1.decode(compliance.encode())).isEqualTo(compliance);
        assertThat(ObjectRetentionMode.NONE.code()).isZero();
        assertThat(ObjectRetentionMode.GOVERNANCE.code()).isEqualTo(1);
        assertThat(ObjectRetentionMode.COMPLIANCE.code()).isEqualTo(2);

        assertInvalid(() -> objectDetail(ObjectRetentionMode.NONE, 1L));
        assertInvalid(() -> objectDetail(ObjectRetentionMode.GOVERNANCE, null));
        assertInvalid(() -> objectDetail(ObjectRetentionMode.COMPLIANCE, null));
        assertInvalid(() -> objectDetail(ObjectRetentionMode.GOVERNANCE, -1L));
        assertInvalid(() -> ObjectRetentionMode.fromCode(3));
    }

    @Test
    void stableProviderTextIsPrintableAsciiOnly() {
        assertInvalid(() -> new ObjectPutDetailV1(hash(1), "version\nsecret", null,
                hash(2), 1, ObjectRetentionMode.NONE, null));
        assertInvalid(() -> new ObjectPutDetailV1(hash(1), "version-1", "etag\u007f",
                hash(2), 1, ObjectRetentionMode.NONE, null));
        assertInvalid(() -> new IpfsPinDetailV1(hash(1), CanonicalCid.fromText(RAW_CID),
                true, "pin-\u00e9"));
    }

    @Test
    void oversizedDetailDataIsRejectedBeforeSnapshot() {
        assertInvalid(() -> new ConnectorDetailDocumentV1(hash(1),
                ConnectorAction.KAFKA_PUBLISH,
                new byte[ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES]));
    }

    private static List<ValuePair> valuePairs() {
        CanonicalCid cid = CanonicalCid.fromText(RAW_CID);
        KafkaPublishDetailV1 kafkaDetail = new KafkaPublishDetailV1(hash(2), 3, 42, 2, 15);
        ConnectorDetailDocumentV1 document = ConnectorDetailDocumentV1.of(hash(9), kafkaDetail);

        return List.of(
                pair(new ConnectorTargetFingerprint(hash(1)),
                        new ConnectorTargetFingerprint(hash(1))),
                pair(new KafkaHeader("trace-id", bytes(1, 2, 3)),
                        new KafkaHeader("trace-id", bytes(1, 2, 3))),
                pair(kafkaCommand(), kafkaCommand()),
                pair(new KafkaPublishReceiptV1(hash(1), 3, 42),
                        new KafkaPublishReceiptV1(hash(1), 3, 42)),
                pair(objectCommand(), objectCommand()),
                pair(new ObjectPutReceiptV1(hash(1), hash(2), hash(3), 15),
                        new ObjectPutReceiptV1(hash(1), hash(2), hash(3), 15)),
                pair(new ObjectVersionFingerprint(hash(1)),
                        new ObjectVersionFingerprint(hash(1))),
                pair(new IpfsCidFingerprint(hash(1)), new IpfsCidFingerprint(hash(1))),
                pair(new IpfsPinReceiptV1(hash(1), hash(2)),
                        new IpfsPinReceiptV1(hash(1), hash(2))),
                pair(new IpfsPinSubmittedRefV1(hash(1), ascii("request-42")),
                        new IpfsPinSubmittedRefV1(hash(1), ascii("request-42"))),
                pair(kafkaDetail, new KafkaPublishDetailV1(hash(2), 3, 42, 2, 15)),
                pair(new ObjectPutDetailV1(hash(2), "version-1", "etag-1", hash(3),
                                15, ObjectRetentionMode.NONE, null),
                        new ObjectPutDetailV1(hash(2), "version-1", "etag-1", hash(3),
                                15, ObjectRetentionMode.NONE, null)),
                pair(new IpfsPinDetailV1(hash(2), cid, true, "pin-42"),
                        new IpfsPinDetailV1(hash(2), cid, true, "pin-42")),
                pair(document, ConnectorDetailDocumentV1.of(hash(9),
                        new KafkaPublishDetailV1(hash(2), 3, 42, 2, 15))),
                pair(ConnectorDetailHash.compute(document), ConnectorDetailHash.compute(document))
        );
    }

    private static KafkaPublishCommandV1 kafkaCommand() {
        return new KafkaPublishCommandV1("broker-v1", "events", bytes(1, 2),
                "application/json", bytes(3, 4),
                List.of(new KafkaHeader("trace-id", bytes(5, 6))));
    }

    private static ObjectPutCommandV1 objectCommand() {
        return new ObjectPutCommandV1("archive-v1", "incoming/doc.json", "docs/doc.json",
                DigestAlgorithm.SHA_256, hash(4), 15, "application/json", "worm-v1");
    }

    private static ObjectPutDetailV1 objectDetail(ObjectRetentionMode mode, Long retainUntil) {
        return new ObjectPutDetailV1(hash(1), "version-1", null, hash(2), 15,
                mode, retainUntil);
    }

    private static ValuePair pair(Object first, Object second) {
        return new ValuePair(first, second);
    }

    private static byte[] unsupportedCodecCid() {
        byte[] cid = new byte[36];
        cid[0] = 1;
        cid[1] = 0x71;
        cid[2] = (byte) CanonicalCid.SHA2_256_MULTIHASH;
        cid[3] = (byte) CanonicalCid.SHA2_256_DIGEST_LENGTH;
        return cid;
    }

    private static byte[] hash(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static void assertInvalid(Runnable action) {
        assertThatExceptionOfType(ConnectorContractException.class)
                .isThrownBy(action::run)
                .satisfies(error -> assertThat(error.code())
                        .isEqualTo(ConnectorErrorCode.INVALID_PAYLOAD));
    }

    private record ValuePair(Object first, Object second) {
    }
}
