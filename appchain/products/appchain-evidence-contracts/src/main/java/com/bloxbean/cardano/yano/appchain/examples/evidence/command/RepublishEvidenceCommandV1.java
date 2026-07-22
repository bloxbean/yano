package com.bloxbean.cardano.yano.appchain.examples.evidence.command;

import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;

import java.util.Arrays;
import java.util.Objects;

/** Canonical next-version publication after the current version is terminal. */
public record RepublishEvidenceCommandV1(String evidenceId,
                                         long businessVersion,
                                         byte[] objectPutCommand,
                                         byte[] expectedObjectDestinationFingerprint,
                                         byte[] ipfsPinCommand,
                                         byte[] expectedIpfsTargetFingerprint,
                                         String kafkaTarget,
                                         String kafkaTopic,
                                         byte[] expectedKafkaDestinationFingerprint)
        implements EvidenceCommandV1 {
    /** Validates fields and requires a version after the initial submission. */
    public RepublishEvidenceCommandV1 {
        evidenceId = EvidenceValidation.evidenceId(evidenceId);
        if (businessVersion < 2) {
            throw EvidenceValidation.invalid();
        }
        objectPutCommand = EvidenceValidation.objectCommand(objectPutCommand).encode();
        expectedObjectDestinationFingerprint = EvidenceValidation.exactBytes(
                expectedObjectDestinationFingerprint, 32);
        ipfsPinCommand = EvidenceValidation.ipfsCommand(ipfsPinCommand).encode();
        expectedIpfsTargetFingerprint = EvidenceValidation.exactBytes(
                expectedIpfsTargetFingerprint, 32);
        EvidenceValidation.kafkaRoute(kafkaTarget, kafkaTopic);
        expectedKafkaDestinationFingerprint = EvidenceValidation.exactBytes(
                expectedKafkaDestinationFingerprint, 32);
    }

    @Override
    public EvidenceCommandOperation operation() {
        return EvidenceCommandOperation.REPUBLISH;
    }

    @Override
    public byte[] objectPutCommand() {
        return objectPutCommand.clone();
    }

    @Override
    public byte[] ipfsPinCommand() {
        return ipfsPinCommand.clone();
    }

    @Override
    public byte[] expectedObjectDestinationFingerprint() {
        return expectedObjectDestinationFingerprint.clone();
    }

    @Override
    public byte[] expectedIpfsTargetFingerprint() {
        return expectedIpfsTargetFingerprint.clone();
    }

    @Override
    public byte[] expectedKafkaDestinationFingerprint() {
        return expectedKafkaDestinationFingerprint.clone();
    }

    /** Returns the validated object-store instruction. */
    public ObjectPutCommandV1 objectPut() {
        return ObjectPutCommandV1.decode(objectPutCommand);
    }

    /** Returns the validated IPFS instruction. */
    public IpfsPinCommandV1 ipfsPin() {
        return IpfsPinCommandV1.decode(ipfsPinCommand);
    }

    @Override
    public byte[] encode() {
        return EvidenceCommandCodec.encodeStorage(this);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RepublishEvidenceCommandV1 command
                && businessVersion == command.businessVersion
                && evidenceId.equals(command.evidenceId)
                && Arrays.equals(objectPutCommand, command.objectPutCommand)
                && Arrays.equals(expectedObjectDestinationFingerprint,
                command.expectedObjectDestinationFingerprint)
                && Arrays.equals(ipfsPinCommand, command.ipfsPinCommand)
                && Arrays.equals(expectedIpfsTargetFingerprint,
                command.expectedIpfsTargetFingerprint)
                && kafkaTarget.equals(command.kafkaTarget)
                && kafkaTopic.equals(command.kafkaTopic)
                && Arrays.equals(expectedKafkaDestinationFingerprint,
                command.expectedKafkaDestinationFingerprint);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(evidenceId, businessVersion, kafkaTarget, kafkaTopic);
        result = 31 * result + Arrays.hashCode(objectPutCommand);
        result = 31 * result + Arrays.hashCode(expectedObjectDestinationFingerprint);
        result = 31 * result + Arrays.hashCode(ipfsPinCommand);
        result = 31 * result + Arrays.hashCode(expectedIpfsTargetFingerprint);
        return 31 * result + Arrays.hashCode(expectedKafkaDestinationFingerprint);
    }
}
