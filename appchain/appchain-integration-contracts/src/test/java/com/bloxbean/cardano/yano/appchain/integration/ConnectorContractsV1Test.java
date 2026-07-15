package com.bloxbean.cardano.yano.appchain.integration;

import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinSubmittedRefV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaHeader;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaPublishReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectVersionFingerprint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ConnectorContractsV1Test {
    private static final HexFormat HEX = HexFormat.of();
    private static final String RAW_CID =
            "bafkreigh2akiscaildc7pmdz6w3m6fy42j2qcdq3q525bs4x36qj2mzyvi";
    private static Properties vectors;

    @BeforeAll
    static void loadVectors() throws IOException {
        vectors = new Properties();
        try (var input = ConnectorContractsV1Test.class.getResourceAsStream(
                "/META-INF/yano/contracts/connectors/v1/golden-vectors.properties")) {
            vectors.load(input);
        }
    }

    @Test
    void kafkaCommandAndReceiptMatchGoldenVectors() {
        KafkaPublishCommandV1 command = kafkaCommand();
        assertThat(hex(command.encode())).isEqualTo(vectors.getProperty("kafka.command"));

        KafkaPublishCommandV1 decoded = KafkaPublishCommandV1.decode(command.encode());
        assertThat(decoded.target()).isEqualTo("primary-v1");
        assertThat(decoded.topic()).isEqualTo("evidence-ready");
        assertThat(decoded.key()).containsExactly(1, 2, 3);
        assertThat(decoded.headers()).extracting(KafkaHeader::name)
                .containsExactly("trace-id", "x-kind");
        assertThat(decoded.encode()).isEqualTo(command.encode());

        KafkaPublishReceiptV1 receipt = new KafkaPublishReceiptV1(
                kafkaDestination().bytes(), 3, 42);
        assertThat(hex(receipt.encode())).isEqualTo(vectors.getProperty("kafka.receipt"));
        assertThat(KafkaPublishReceiptV1.decode(receipt.encode()).encode())
                .isEqualTo(receipt.encode());
        assertThat(receipt.encode()).hasSizeLessThanOrEqualTo(ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
    }

    @Test
    void objectCommandAndReceiptMatchGoldenVectors() {
        ObjectPutCommandV1 command = objectCommand();
        assertThat(hex(command.encode())).isEqualTo(vectors.getProperty("object.command"));
        assertThat(ObjectPutCommandV1.decode(command.encode()).encode()).isEqualTo(command.encode());

        ObjectPutReceiptV1 receipt = new ObjectPutReceiptV1(
                objectDestination().bytes(), ObjectVersionFingerprint.compute(
                        vector("object.version.provider-version-id")).bytes(),
                repeat(0x44), 15);
        assertThat(hex(receipt.encode())).isEqualTo(vectors.getProperty("object.receipt"));
        assertThat(ObjectPutReceiptV1.decode(receipt.encode()).encode()).isEqualTo(receipt.encode());
        assertThat(receipt.encode()).hasSizeLessThanOrEqualTo(ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
    }

    @Test
    void ipfsCommandAndReferencesMatchGoldenVectors() {
        CanonicalCid cid = CanonicalCid.fromText(vector("ipfs.cid.input-text"));
        IpfsPinCommandV1 command = new IpfsPinCommandV1("kubo-v1", cid, true, "single-v1");
        assertThat(hex(command.encode())).isEqualTo(vectors.getProperty("ipfs.command"));
        assertThat(IpfsPinCommandV1.decode(command.encode()).encode()).isEqualTo(command.encode());

        IpfsPinReceiptV1 receipt = new IpfsPinReceiptV1(
                ipfsTarget().bytes(), IpfsCidFingerprint.compute(cid).bytes());
        assertThat(hex(receipt.encode())).isEqualTo(vectors.getProperty("ipfs.receipt"));
        assertThat(IpfsPinReceiptV1.decode(receipt.encode()).encode()).isEqualTo(receipt.encode());

        IpfsPinSubmittedRefV1 submitted = new IpfsPinSubmittedRefV1(
                ipfsTarget().bytes(),
                "request-42".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(hex(submitted.encode())).isEqualTo(vectors.getProperty("ipfs.submitted"));
        assertThat(IpfsPinSubmittedRefV1.decode(submitted.encode()).encode())
                .isEqualTo(submitted.encode());
        assertThat(submitted.encode()).hasSizeLessThanOrEqualTo(ConnectorLimits.MAX_EXTERNAL_REF_BYTES);
    }

    @Test
    void allDecodersRejectTrailingNonPreferredTaggedIndefiniteAndExtraForms() {
        byte[] canonical = kafkaCommand().encode();
        assertInvalid(() -> KafkaPublishCommandV1.decode(concat(canonical, bytes(0))));

        byte[] nonPreferredVersion = concat(bytes(0x87, 0x18, 0x01),
                Arrays.copyOfRange(canonical, 2, canonical.length));
        assertInvalid(() -> KafkaPublishCommandV1.decode(nonPreferredVersion));

        assertInvalid(() -> KafkaPublishCommandV1.decode(concat(bytes(0xc0), canonical)));

        byte[] indefinite = concat(bytes(0x9f), Arrays.copyOfRange(canonical, 1, canonical.length),
                bytes(0xff));
        assertInvalid(() -> KafkaPublishCommandV1.decode(indefinite));

        byte[] extraField = concat(bytes(0x88), Arrays.copyOfRange(canonical, 1, canonical.length),
                bytes(0));
        assertInvalid(() -> KafkaPublishCommandV1.decode(extraField));
    }

    @Test
    void unknownVersionsHaveTheirOwnStableClassification() {
        byte[] command = kafkaCommand().encode();
        byte[] unknownVersion = command.clone();
        unknownVersion[1] = 2;
        assertUnsupported(() -> KafkaPublishCommandV1.decode(unknownVersion));
        assertUnsupported(() -> KafkaPublishCommandV1.decode(bytes(0x81, 0x02)));
        assertUnsupported(() -> KafkaPublishCommandV1.decode(bytes(
                0x88, 0x02, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void hostileDeclaredLengthsAndNestingFailBeforeDecoderAllocationOrRecursion() {
        assertInvalid(() -> KafkaPublishCommandV1.decode(bytes(
                0x87, 0x01, 0x7a, 0x05, 0xf5, 0xe1, 0x00)));
        assertInvalid(() -> KafkaPublishCommandV1.decode(bytes(
                0x87, 0x01, 0x61, 'a', 0x61, 'b', 0x5a, 0x05, 0xf5, 0xe1, 0x00)));
        assertInvalid(() -> KafkaPublishCommandV1.decode(bytes(
                0x87, 0x01, 0x61, 'a', 0x61, 'b', 0x40, 0x63, 'a', '/', 'b', 0x40,
                0x9a, 0x05, 0xf5, 0xe1, 0x00)));

        byte[] deeplyNested = new byte[4_008];
        deeplyNested[0] = (byte) 0x87;
        deeplyNested[1] = 0x01;
        Arrays.fill(deeplyNested, 2, 4_002, (byte) 0x81);
        deeplyNested[4_002] = 0x61;
        deeplyNested[4_003] = 'a';
        deeplyNested[4_004] = 0;
        deeplyNested[4_005] = 0;
        deeplyNested[4_006] = 0;
        deeplyNested[4_007] = 0;
        assertInvalid(() -> KafkaPublishCommandV1.decode(deeplyNested));
    }

    @Test
    void kafkaBoundsAndHeaderPolicyAreFailClosed() {
        assertInvalid(() -> new KafkaPublishCommandV1("https://broker", "events", new byte[0],
                "application/json", new byte[0], List.of()));
        assertInvalid(() -> new KafkaPublishCommandV1("primary-v1", "events", new byte[257],
                "application/json", new byte[0], List.of()));
        assertInvalid(() -> new KafkaPublishCommandV1("primary-v1", "events", new byte[0],
                "application/json; charset=utf-8", new byte[0], List.of()));
        assertInvalid(() -> new KafkaPublishCommandV1("primary-v1", "events", new byte[0],
                "application/json", new byte[8_193], List.of()));
        assertInvalid(() -> new KafkaHeader("yano-effect-id", new byte[0]));
        assertInvalid(() -> new KafkaHeader("Trace-Id", new byte[0]));
        assertInvalid(() -> new KafkaHeader("a", new byte[257]));
        assertInvalid(() -> new KafkaPublishCommandV1("primary-v1", "events", new byte[0],
                "application/json", new byte[0], List.of(
                new KafkaHeader("same", new byte[0]), new KafkaHeader("same", new byte[0]))));
    }

    @Test
    void portableAliasAndContentTypeBoundariesAreExact() {
        String alias63 = "a" + "b".repeat(62);
        String contentType127 = "a".repeat(62) + "/" + "b".repeat(64);
        KafkaPublishCommandV1 boundary = new KafkaPublishCommandV1(
                alias63, alias63, new byte[256], contentType127, new byte[8_192], List.of());
        assertThat(KafkaPublishCommandV1.decode(boundary.encode()).encode())
                .isEqualTo(boundary.encode());

        assertInvalid(() -> new KafkaPublishCommandV1("a".repeat(64), "events", new byte[0],
                "application/json", new byte[0], List.of()));
        assertInvalid(() -> new KafkaPublishCommandV1("1target", "events", new byte[0],
                "application/json", new byte[0], List.of()));
        assertInvalid(() -> new KafkaPublishCommandV1("Target", "events", new byte[0],
                "application/json", new byte[0], List.of()));
        assertInvalid(() -> new KafkaPublishCommandV1("target_v1", "events", new byte[0],
                "application/json", new byte[0], List.of()));
        assertInvalid(() -> new KafkaPublishCommandV1("target", "events", new byte[0],
                "a".repeat(63) + "/" + "b".repeat(64), new byte[0], List.of()));
    }

    @Test
    void kafkaHeaderCountAndAggregateBoundariesAreExact() {
        List<KafkaHeader> exactlyBounded = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> new KafkaHeader(
                        ("h" + index + "x".repeat(30)).substring(0, 32), new byte[224]))
                .toList();
        KafkaPublishCommandV1 command = new KafkaPublishCommandV1(
                "primary-v1", "events", new byte[0], "application/json", new byte[0],
                exactlyBounded);
        assertThat(command.headers()).hasSize(8);

        List<KafkaHeader> tooLarge = new java.util.ArrayList<>(exactlyBounded);
        tooLarge.set(0, new KafkaHeader(exactlyBounded.get(0).name(), new byte[225]));
        assertInvalid(() -> new KafkaPublishCommandV1(
                "primary-v1", "events", new byte[0], "application/json", new byte[0], tooLarge));

        List<KafkaHeader> tooMany = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> new KafkaHeader("h-" + index, new byte[0]))
                .toList();
        assertInvalid(() -> new KafkaPublishCommandV1(
                "primary-v1", "events", new byte[0], "application/json", new byte[0], tooMany));
    }

    @Test
    void nonCanonicalKafkaHeaderOrderIsRejectedOnDecode() {
        KafkaPublishCommandV1 normalized = new KafkaPublishCommandV1(
                "primary-v1", "events", new byte[0], "application/json", new byte[0],
                List.of(new KafkaHeader("z-name", new byte[0]),
                        new KafkaHeader("a-name", new byte[0])));
        assertThat(normalized.headers()).extracting(KafkaHeader::name)
                .containsExactly("a-name", "z-name");

        byte[] canonical = normalized.encode();
        byte[] first = "a-name".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] second = "z-name".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] unsorted = canonical.clone();
        int firstIndex = indexOf(unsorted, first);
        int secondIndex = indexOf(unsorted, second);
        System.arraycopy(second, 0, unsorted, firstIndex, second.length);
        System.arraycopy(first, 0, unsorted, secondIndex, first.length);
        assertInvalid(() -> KafkaPublishCommandV1.decode(unsorted));
    }

    @Test
    void objectKeysDigestAndSizeAreStrictlyBounded() {
        assertInvalid(() -> objectCommand("../secret", "evidence/doc.json", 15));
        assertInvalid(() -> objectCommand("incoming//doc.json", "evidence/doc.json", 15));
        assertInvalid(() -> objectCommand("incoming/%2e%2e/doc.json", "evidence/doc.json", 15));
        assertInvalid(() -> objectCommand("incoming/doc.json", "/absolute/doc.json", 15));
        assertInvalid(() -> objectCommand("incoming/doc.json", "evidence/doc.json",
                ObjectPutCommandV1.MAX_OBJECT_BYTES + 1));
        assertInvalid(() -> new ObjectPutCommandV1("archive-v1", "incoming/doc.json",
                "evidence/doc.json", DigestAlgorithm.SHA_256, new byte[31], 15,
                "application/json", null));
    }

    @Test
    void objectKeyAndReceiptMaximumBoundariesAreExact() {
        String key512 = "a".repeat(128) + "/" + "b".repeat(127) + "/"
                + "c".repeat(127) + "/" + "d".repeat(127);
        ObjectPutCommandV1 command = objectCommand(key512, key512,
                ObjectPutCommandV1.MAX_OBJECT_BYTES);
        assertThat(command.sourceKey()).hasSize(512);
        assertThat(ObjectPutCommandV1.decode(command.encode()).encode()).isEqualTo(command.encode());

        assertInvalid(() -> objectCommand(key512 + "e", "evidence/doc.json", 1));
        assertInvalid(() -> objectCommand("a".repeat(129), "evidence/doc.json", 1));
        assertInvalid(() -> objectCommand(String.join("/", java.util.Collections.nCopies(33, "a")),
                "evidence/doc.json", 1));

        ObjectPutReceiptV1 receipt = new ObjectPutReceiptV1(
                repeat(1), repeat(2), repeat(3), ObjectPutCommandV1.MAX_OBJECT_BYTES);
        assertThat(receipt.encode()).hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void ipfsV1PolicyAllowsOnlyRawOrDagPbSha256() {
        assertInvalid(() -> new IpfsPinCommandV1("kubo-v1",
                CanonicalCid.fromBytes(bytes(1, 0x71, 0x12, 1, 1)), true, null));
        assertInvalid(() -> new IpfsPinCommandV1("kubo-v1",
                CanonicalCid.fromBytes(bytes(1, 0x55, 0x13, 1, 1)), true, null));
        assertInvalid(() -> new IpfsPinCommandV1("https://provider", CanonicalCid.fromText(RAW_CID),
                true, null));
    }

    @Test
    void submittedReferenceBoundaryFitsTheFrameworkLimit() {
        IpfsPinSubmittedRefV1 maximum = new IpfsPinSubmittedRefV1(repeat(1), new byte[88]);
        assertThat(maximum.encode()).hasSizeLessThanOrEqualTo(128);
        assertThat(IpfsPinSubmittedRefV1.decode(maximum.encode()).providerRequestId()).hasSize(88);
        assertInvalid(() -> new IpfsPinSubmittedRefV1(repeat(1), new byte[89]));
    }

    @Test
    void destinationFingerprintsAreStableCredentialFreeAndDomainSeparated() {
        ConnectorTargetFingerprint kafka = kafkaDestination();
        ConnectorTargetFingerprint kafkaAgain = kafkaDestination();
        ConnectorTargetFingerprint object = objectDestination();
        ConnectorTargetFingerprint ipfs = ipfsTarget();
        CanonicalCid canonicalCid = CanonicalCid.fromText(vector("ipfs.cid.input-text"));
        IpfsCidFingerprint cid = IpfsCidFingerprint.compute(canonicalCid);
        ObjectVersionFingerprint version = ObjectVersionFingerprint.compute(
                vector("object.version.provider-version-id"));

        assertThat(kafka.bytes()).isEqualTo(kafkaAgain.bytes()).hasSize(32);
        assertThat(kafka.bytes()).isNotEqualTo(object.bytes()).isNotEqualTo(ipfs.bytes());
        assertThat(vector("kafka.destination.domain"))
                .isEqualTo(ConnectorFingerprintDomain.KAFKA_DESTINATION.value());
        assertThat(vector("object.destination.domain"))
                .isEqualTo(ConnectorFingerprintDomain.OBJECT_DESTINATION.value());
        assertThat(vector("ipfs.target.domain"))
                .isEqualTo(ConnectorFingerprintDomain.IPFS_TARGET.value());
        assertThat(vector("object.version.domain")).isEqualTo(ObjectVersionFingerprint.DOMAIN);
        assertThat(vector("ipfs.cid.domain")).isEqualTo(IpfsCidFingerprint.DOMAIN);
        assertThat(hex(kafka.bytes())).isEqualTo(vectors.getProperty(
                "kafka.destination-fingerprint"));
        assertThat(hex(object.bytes())).isEqualTo(vectors.getProperty(
                "object.destination-fingerprint"));
        assertThat(hex(ipfs.bytes())).isEqualTo(vectors.getProperty(
                "ipfs.target-fingerprint"));
        assertThat(hex(cid.bytes())).isEqualTo(vectors.getProperty("ipfs.cid-fingerprint"));
        assertThat(hex(version.bytes())).isEqualTo(vectors.getProperty(
                "object.version-fingerprint"));
        assertThat(vector("ipfs.cid.input-text")).isEqualTo(RAW_CID);
        assertThat(hex(canonicalCid.bytes())).isEqualTo(
                vectors.getProperty("ipfs.cid-binary"));
        assertThat(hex(CanonicalCid.fromText(vector("ipfs.cidv0.input-text")).bytes()))
                .isEqualTo(vector("ipfs.cidv0.normalized-binary"));
        assertInvalid(() -> KafkaDestinationFingerprint.compute("broker-v1", "topic\nsecret"));
        assertInvalid(() -> ObjectDestinationFingerprint.compute(
                "archive-v1", "evidence", "verified/", "doc-1.json",
                "sse-v1", "worm-v1"));
    }

    @Test
    void failuresAreBoundedAsciiMachineCodesOnly() {
        Map<ConnectorErrorCode, FailureDisposition> expected = Map.ofEntries(
                Map.entry(ConnectorErrorCode.INVALID_PAYLOAD, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.UNSUPPORTED_VERSION, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.UNKNOWN_TARGET, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.TARGET_DISABLED, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.TARGET_CHANGED, FailureDisposition.OPERATOR_ACTION),
                Map.entry(ConnectorErrorCode.POLICY_DENIED, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.AUTH_UNAVAILABLE, FailureDisposition.OPERATOR_ACTION),
                Map.entry(ConnectorErrorCode.RATE_LIMITED, FailureDisposition.RETRYABLE),
                Map.entry(ConnectorErrorCode.SERVICE_UNAVAILABLE, FailureDisposition.RETRYABLE),
                Map.entry(ConnectorErrorCode.ACK_UNKNOWN, FailureDisposition.PROBE_REQUIRED),
                Map.entry(ConnectorErrorCode.SOURCE_UNAVAILABLE, FailureDisposition.RETRYABLE),
                Map.entry(ConnectorErrorCode.SOURCE_MISMATCH, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.CONTENT_UNAVAILABLE, FailureDisposition.RETRYABLE),
                Map.entry(ConnectorErrorCode.CONTENT_NOT_FOUND, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.DESTINATION_CONFLICT, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.PROVIDER_REJECTED, FailureDisposition.DEFINITIVE),
                Map.entry(ConnectorErrorCode.DETAIL_ARCHIVE_FAILED, FailureDisposition.RETRYABLE),
                Map.entry(ConnectorErrorCode.SHUTDOWN, FailureDisposition.RETRYABLE),
                Map.entry(ConnectorErrorCode.INTERNAL_ERROR, FailureDisposition.RETRYABLE));

        assertThat(expected).hasSize(ConnectorErrorCode.values().length);
        for (ConnectorErrorCode code : ConnectorErrorCode.values()) {
            assertThat(code.wireCode()).matches("[A-Z][A-Z0-9_]{0,63}");
            assertThat(code.disposition()).isEqualTo(expected.get(code));
            assertThat(ConnectorFailure.reason(code)).hasSizeLessThanOrEqualTo(64);
            assertThat(ConnectorFailure.retryable(code))
                    .isEqualTo(code.disposition().retryable());
            assertThat(ConnectorErrorCode.fromWireCode(code.wireCode())).isEqualTo(code);
        }
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ConnectorErrorCode.fromWireCode("vendor: password=secret"));
    }

    @Test
    void byteArrayModelsAreDefensivelyCopied() {
        byte[] key = bytes(1, 2, 3);
        KafkaPublishCommandV1 command = new KafkaPublishCommandV1(
                "primary-v1", "events", key, "application/json", new byte[0], List.of());
        key[0] = 99;
        byte[] returned = command.key();
        returned[0] = 88;
        assertThat(command.key()).containsExactly(1, 2, 3);

        byte[] fingerprint = repeat(0x11);
        KafkaPublishReceiptV1 receipt = new KafkaPublishReceiptV1(fingerprint, 0, 0);
        fingerprint[0] = 0;
        byte[] returnedFingerprint = receipt.destinationFingerprint();
        returnedFingerprint[0] = 0;
        assertThat(receipt.destinationFingerprint()[0]).isEqualTo((byte) 0x11);
    }

    private static KafkaPublishCommandV1 kafkaCommand() {
        return new KafkaPublishCommandV1(
                "primary-v1", "evidence-ready", bytes(1, 2, 3), "application/json",
                "{\"id\":\"doc-1\"}".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                List.of(new KafkaHeader("trace-id", bytes(10, 11)),
                        new KafkaHeader("x-kind", "ready".getBytes(
                                java.nio.charset.StandardCharsets.US_ASCII))));
    }

    private static ObjectPutCommandV1 objectCommand() {
        return objectCommand("incoming/doc-1.json", "evidence/doc-1.json", 15);
    }

    private static ObjectPutCommandV1 objectCommand(String source, String destination, long size) {
        return new ObjectPutCommandV1("archive-v1", source, destination,
                DigestAlgorithm.SHA_256, repeat(0x22), size,
                "application/json", "worm-v1");
    }

    private static ConnectorTargetFingerprint kafkaDestination() {
        return KafkaDestinationFingerprint.compute(
                vector("kafka.destination.target-id"),
                vector("kafka.destination.physical-topic"));
    }

    private static ConnectorTargetFingerprint objectDestination() {
        return ObjectDestinationFingerprint.compute(
                vector("object.destination.target-id"),
                vector("object.destination.bucket"),
                vector("object.destination.prefix"),
                vector("object.destination.relative-key"),
                vector("object.destination.encryption-policy-id"),
                vector("object.destination.retention-policy-id"));
    }

    private static ConnectorTargetFingerprint ipfsTarget() {
        return IpfsTargetFingerprint.compute(vector("ipfs.target.target-id"));
    }

    private static String vector(String name) {
        return vectors.getProperty(name);
    }

    private static void assertInvalid(Runnable action) {
        assertThatExceptionOfType(ConnectorContractException.class)
                .isThrownBy(action::run)
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ConnectorErrorCode.INVALID_PAYLOAD);
                    assertThat(error).hasMessage("INVALID_PAYLOAD");
                });
    }

    private static void assertUnsupported(Runnable action) {
        assertThatExceptionOfType(ConnectorContractException.class)
                .isThrownBy(action::run)
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ConnectorErrorCode.UNSUPPORTED_VERSION);
                    assertThat(error).hasMessage("UNSUPPORTED_VERSION");
                });
    }

    private static byte[] repeat(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        int length = Arrays.stream(arrays).mapToInt(value -> value.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }
}
