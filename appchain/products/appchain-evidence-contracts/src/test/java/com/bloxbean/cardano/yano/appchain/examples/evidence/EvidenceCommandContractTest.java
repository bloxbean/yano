package com.bloxbean.cardano.yano.appchain.examples.evidence;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandCodec;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.NotifyEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.RepublishEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceCommandContractTest {
    @Test
    void allCommandsRoundTripWithSchemaAndBusinessVersionsSeparated() {
        SubmitEvidenceCommandV1 submit = EvidenceFixtures.submit();
        NotifyEvidenceCommandV1 notify = new NotifyEvidenceCommandV1(EvidenceFixtures.ID, 1);
        RepublishEvidenceCommandV1 republish = EvidenceFixtures.republish();

        assertThat(EvidenceCommandCodec.decode(submit.encode())).isEqualTo(submit);
        assertThat(EvidenceCommandCodec.decode(notify.encode())).isEqualTo(notify);
        assertThat(EvidenceCommandCodec.decode(republish.encode())).isEqualTo(republish);
        assertThat(submit.encode()).hasSizeLessThanOrEqualTo(EvidenceContract.MAX_COMMAND_BYTES);
        assertThat(republish.businessVersion()).isEqualTo(2);
    }

    @Test
    void submitAndRepublishRequireThreeResolvedDestinationCommitments() {
        SubmitEvidenceCommandV1 submit = EvidenceFixtures.submit();
        assertThat(submit.expectedObjectDestinationFingerprint())
                .isEqualTo(EvidenceFixtures.OBJECT_DESTINATION);
        assertThat(submit.expectedIpfsTargetFingerprint())
                .isEqualTo(EvidenceFixtures.IPFS_TARGET);
        assertThat(submit.expectedKafkaDestinationFingerprint())
                .isEqualTo(EvidenceFixtures.KAFKA_DESTINATION);

        assertInvalid(() -> new SubmitEvidenceCommandV1(
                EvidenceFixtures.ID, 1,
                EvidenceFixtures.objectCommand().encode(), new byte[31],
                EvidenceFixtures.ipfsCommand().encode(), EvidenceFixtures.IPFS_TARGET,
                "primary-v1", "evidence-ready", EvidenceFixtures.KAFKA_DESTINATION));
        assertInvalid(() -> new RepublishEvidenceCommandV1(
                EvidenceFixtures.ID, 2,
                EvidenceFixtures.objectCommand().encode(), EvidenceFixtures.OBJECT_DESTINATION,
                EvidenceFixtures.ipfsCommand().encode(), EvidenceFixtures.IPFS_TARGET,
                "primary-v1", "evidence-ready", new byte[33]));
    }

    @Test
    void commandsRejectInvalidDomainVersionsIdsAndKafkaAliases() {
        SubmitEvidenceCommandV1 submit = EvidenceFixtures.submit();
        assertInvalid(() -> new SubmitEvidenceCommandV1(
                submit.evidenceId(), 2, submit.objectPutCommand(),
                submit.expectedObjectDestinationFingerprint(), submit.ipfsPinCommand(),
                submit.expectedIpfsTargetFingerprint(), submit.kafkaTarget(),
                submit.kafkaTopic(), submit.expectedKafkaDestinationFingerprint()));
        assertInvalid(() -> new RepublishEvidenceCommandV1(
                submit.evidenceId(), 1, submit.objectPutCommand(),
                submit.expectedObjectDestinationFingerprint(), submit.ipfsPinCommand(),
                submit.expectedIpfsTargetFingerprint(), submit.kafkaTarget(),
                submit.kafkaTopic(), submit.expectedKafkaDestinationFingerprint()));
        assertInvalid(() -> new NotifyEvidenceCommandV1("Batch_001", 1));
        assertInvalid(() -> new NotifyEvidenceCommandV1("a".repeat(64), 1));
        assertInvalid(() -> new NotifyEvidenceCommandV1("batch-001", 0));
        assertInvalid(() -> new SubmitEvidenceCommandV1(
                submit.evidenceId(), 1, submit.objectPutCommand(),
                submit.expectedObjectDestinationFingerprint(), submit.ipfsPinCommand(),
                submit.expectedIpfsTargetFingerprint(), "raw:9092", submit.kafkaTopic(),
                submit.expectedKafkaDestinationFingerprint()));
    }

    @Test
    void nestedConnectorPayloadsMustBeTheirExactFrozenCanonicalBytes() {
        SubmitEvidenceCommandV1 submit = EvidenceFixtures.submit();
        byte[] objectWithTrailing = append(submit.objectPutCommand(), (byte) 0);
        assertInvalid(() -> new SubmitEvidenceCommandV1(
                submit.evidenceId(), 1, objectWithTrailing,
                submit.expectedObjectDestinationFingerprint(), submit.ipfsPinCommand(),
                submit.expectedIpfsTargetFingerprint(), submit.kafkaTarget(),
                submit.kafkaTopic(), submit.expectedKafkaDestinationFingerprint()));

        byte[] ipfsUnknownVersion = submit.ipfsPinCommand();
        ipfsUnknownVersion[1] = 2;
        assertInvalid(() -> new SubmitEvidenceCommandV1(
                submit.evidenceId(), 1, submit.objectPutCommand(),
                submit.expectedObjectDestinationFingerprint(), ipfsUnknownVersion,
                submit.expectedIpfsTargetFingerprint(), submit.kafkaTarget(),
                submit.kafkaTopic(), submit.expectedKafkaDestinationFingerprint()));
    }

    @Test
    void decoderRejectsLegacyShapeUnknownOpcodeAndNonCanonicalForms() {
        SubmitEvidenceCommandV1 submit = EvidenceFixtures.submit();
        byte[] canonical = submit.encode();

        byte[] unknownVersion = canonical.clone();
        unknownVersion[1] = 2;
        assertThatThrownBy(() -> EvidenceCommandCodec.decode(unknownVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNSUPPORTED_VERSION");

        byte[] unknownOpcode = canonical.clone();
        unknownOpcode[2] = 3;
        assertInvalid(() -> EvidenceCommandCodec.decode(unknownOpcode));
        assertInvalid(() -> EvidenceCommandCodec.decode(append(canonical, (byte) 0)));
        assertInvalid(() -> EvidenceCommandCodec.decode(prepend((byte) 0xc0, canonical)));
        assertInvalid(() -> EvidenceCommandCodec.decode(new byte[4_097]));

        Array legacy = new Array();
        legacy.add(new UnsignedInteger(1));
        legacy.add(new UnsignedInteger(0));
        legacy.add(new UnicodeString(EvidenceFixtures.ID));
        legacy.add(new UnsignedInteger(1));
        legacy.add(new ByteString(submit.objectPutCommand()));
        legacy.add(new ByteString(submit.ipfsPinCommand()));
        legacy.add(new UnicodeString(submit.kafkaTarget()));
        legacy.add(new UnicodeString(submit.kafkaTopic()));
        assertInvalid(() -> EvidenceCommandCodec.decode(CanonicalCbor.encode(legacy)));

        byte[] indefinite = append(canonical.clone(), (byte) 0xff);
        indefinite[0] = (byte) 0x9f;
        assertInvalid(() -> EvidenceCommandCodec.decode(indefinite));
    }

    @Test
    void byteFieldsAreDefensivelyCopied() {
        SubmitEvidenceCommandV1 submit = EvidenceFixtures.submit();
        byte[] first = submit.objectPutCommand();
        first[0] ^= 0x7f;
        assertThat(submit.objectPutCommand()).isEqualTo(EvidenceFixtures.objectCommand().encode());

        byte[] fingerprint = submit.expectedKafkaDestinationFingerprint();
        Arrays.fill(fingerprint, (byte) 0);
        assertThat(submit.expectedKafkaDestinationFingerprint())
                .isEqualTo(EvidenceFixtures.KAFKA_DESTINATION);
    }

    private static void assertInvalid(Runnable runnable) {
        assertThatThrownBy(runnable::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] append(byte[] source, byte value) {
        byte[] result = Arrays.copyOf(source, source.length + 1);
        result[result.length - 1] = value;
        return result;
    }

    private static byte[] prepend(byte value, byte[] source) {
        byte[] result = new byte[source.length + 1];
        result[0] = value;
        System.arraycopy(source, 0, result, 1, source.length);
        return result;
    }
}
