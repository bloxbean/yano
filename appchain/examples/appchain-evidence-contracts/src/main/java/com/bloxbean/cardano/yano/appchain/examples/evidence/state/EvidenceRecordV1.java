package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable evidence business-version state.
 *
 * <p>Connector terminal tuples are retained exactly. Receipt interpretation is
 * deliberately deferred to {@link EvidenceStatus#derive(EvidenceRecordV1)} so
 * a malformed confirmed reference produces a deterministic failure status,
 * never an exception while reading committed state.</p>
 *
 * <p>A notification effect may be emitted either by an explicit notify command
 * ({@code notifyMessageId} is present) or directly while incorporating the
 * storage result that makes the evidence ready ({@code notifyMessageId} is
 * absent). A notify message without a notification effect is never valid.</p>
 */
public record EvidenceRecordV1(String evidenceId,
                               long businessVersion,
                               byte[] ownerPublicKey,
                               byte[] submitMessageId,
                               byte[] objectPutCommand,
                               byte[] expectedObjectDestinationFingerprint,
                               EvidenceEffectRef objectEffect,
                               EvidenceTerminalResultV1 objectTerminal,
                               byte[] ipfsPinCommand,
                               byte[] expectedIpfsTargetFingerprint,
                               EvidenceEffectRef ipfsEffect,
                               EvidenceTerminalResultV1 ipfsTerminal,
                               String kafkaTarget,
                               String kafkaTopic,
                               byte[] expectedKafkaDestinationFingerprint,
                               byte[] notifyMessageId,
                               EvidenceEffectRef notificationEffect,
                               EvidenceTerminalResultV1 notificationTerminal) {

    /** Validates structure, connector commands, identities, and causal heights. */
    public EvidenceRecordV1 {
        evidenceId = EvidenceValidation.evidenceId(evidenceId);
        businessVersion = EvidenceValidation.positiveVersion(businessVersion);
        ownerPublicKey = EvidenceValidation.exactBytes(ownerPublicKey,
                EvidenceContract.HASH_BYTES);
        submitMessageId = EvidenceValidation.exactBytes(submitMessageId,
                EvidenceContract.HASH_BYTES);
        objectPutCommand = EvidenceValidation.objectCommand(objectPutCommand).encode();
        expectedObjectDestinationFingerprint = EvidenceValidation.exactBytes(
                expectedObjectDestinationFingerprint, EvidenceContract.HASH_BYTES);
        objectEffect = Objects.requireNonNull(objectEffect, "objectEffect");
        validateResultHeight(objectEffect, objectTerminal);
        ipfsPinCommand = EvidenceValidation.ipfsCommand(ipfsPinCommand).encode();
        expectedIpfsTargetFingerprint = EvidenceValidation.exactBytes(
                expectedIpfsTargetFingerprint, EvidenceContract.HASH_BYTES);
        ipfsEffect = Objects.requireNonNull(ipfsEffect, "ipfsEffect");
        if (objectEffect.height() != ipfsEffect.height()
                || objectEffect.ordinal() == ipfsEffect.ordinal()) {
            throw EvidenceValidation.invalid();
        }
        validateResultHeight(ipfsEffect, ipfsTerminal);
        EvidenceValidation.kafkaRoute(kafkaTarget, kafkaTopic);
        expectedKafkaDestinationFingerprint = EvidenceValidation.exactBytes(
                expectedKafkaDestinationFingerprint, EvidenceContract.HASH_BYTES);

        if (notifyMessageId != null) {
            notifyMessageId = EvidenceValidation.exactBytes(notifyMessageId,
                    EvidenceContract.HASH_BYTES);
        }
        if (notifyMessageId != null && notificationEffect == null) {
            throw EvidenceValidation.invalid();
        }
        if (notificationTerminal != null && notificationEffect == null) {
            throw EvidenceValidation.invalid();
        }
        if (notificationEffect != null) {
            if (!EvidenceStatus.validObjectConfirmation(objectPutCommand,
                    expectedObjectDestinationFingerprint, objectTerminal)
                    || !EvidenceStatus.validIpfsConfirmation(ipfsPinCommand,
                    expectedIpfsTargetFingerprint, ipfsTerminal)) {
                throw EvidenceValidation.invalid();
            }
            long readyHeight = Math.max(objectTerminal.resultHeight(),
                    ipfsTerminal.resultHeight());
            if (notificationEffect.height() < readyHeight
                    || notificationEffect.equals(objectEffect)
                    || notificationEffect.equals(ipfsEffect)) {
                throw EvidenceValidation.invalid();
            }
        }
        validateResultHeight(notificationEffect, notificationTerminal);
    }

    @Override
    public byte[] ownerPublicKey() {
        return ownerPublicKey.clone();
    }

    @Override
    public byte[] submitMessageId() {
        return submitMessageId.clone();
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

    @Override
    public byte[] notifyMessageId() {
        return notifyMessageId == null ? null : notifyMessageId.clone();
    }

    /** Returns the validated object-store command. */
    public ObjectPutCommandV1 objectPut() {
        return ObjectPutCommandV1.decode(objectPutCommand);
    }

    /** Returns the validated IPFS command. */
    public IpfsPinCommandV1 ipfsPin() {
        return IpfsPinCommandV1.decode(ipfsPinCommand);
    }

    /** Encodes the exact 19-field v1 immutable record. */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(EvidenceContract.SCHEMA_VERSION));
        root.add(new UnicodeString(evidenceId));
        root.add(new UnsignedInteger(businessVersion));
        root.add(new ByteString(ownerPublicKey));
        root.add(new ByteString(submitMessageId));
        root.add(new ByteString(objectPutCommand));
        root.add(new ByteString(expectedObjectDestinationFingerprint));
        root.add(objectEffect.toDataItem());
        root.add(EvidenceStateCbor.nullable(objectTerminal));
        root.add(new ByteString(ipfsPinCommand));
        root.add(new ByteString(expectedIpfsTargetFingerprint));
        root.add(ipfsEffect.toDataItem());
        root.add(EvidenceStateCbor.nullable(ipfsTerminal));
        root.add(new UnicodeString(kafkaTarget));
        root.add(new UnicodeString(kafkaTopic));
        root.add(new ByteString(expectedKafkaDestinationFingerprint));
        root.add(EvidenceStateCbor.nullable(notifyMessageId));
        root.add(EvidenceStateCbor.nullable(notificationEffect));
        root.add(EvidenceStateCbor.nullable(notificationTerminal));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, EvidenceContract.MAX_RECORD_BYTES);
        return encoded;
    }

    /** Decodes and validates one strict canonical immutable record. */
    public static EvidenceRecordV1 decode(byte[] encoded) {
        Array root = CanonicalCbor.decodeArray(encoded, EvidenceContract.MAX_RECORD_BYTES, 19);
        List<DataItem> fields = CanonicalCbor.items(root);
        return new EvidenceRecordV1(
                CanonicalCbor.text(fields.get(1)),
                CanonicalCbor.uint(fields.get(2)),
                CanonicalCbor.bytes(fields.get(3)),
                CanonicalCbor.bytes(fields.get(4)),
                CanonicalCbor.bytes(fields.get(5)),
                CanonicalCbor.bytes(fields.get(6)),
                EvidenceEffectRef.fromDataItem(fields.get(7)),
                EvidenceStateCbor.nullableTerminal(fields.get(8)),
                CanonicalCbor.bytes(fields.get(9)),
                CanonicalCbor.bytes(fields.get(10)),
                EvidenceEffectRef.fromDataItem(fields.get(11)),
                EvidenceStateCbor.nullableTerminal(fields.get(12)),
                CanonicalCbor.text(fields.get(13)),
                CanonicalCbor.text(fields.get(14)),
                CanonicalCbor.bytes(fields.get(15)),
                EvidenceStateCbor.nullableBytes(fields.get(16)),
                EvidenceStateCbor.nullableEffectRef(fields.get(17)),
                EvidenceStateCbor.nullableTerminal(fields.get(18)));
    }

    private static void validateResultHeight(EvidenceEffectRef effect,
                                             EvidenceTerminalResultV1 terminal) {
        if (terminal != null && (effect == null || terminal.resultHeight() <= effect.height())) {
            throw EvidenceValidation.invalid();
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceRecordV1 record
                && businessVersion == record.businessVersion
                && evidenceId.equals(record.evidenceId)
                && Arrays.equals(ownerPublicKey, record.ownerPublicKey)
                && Arrays.equals(submitMessageId, record.submitMessageId)
                && Arrays.equals(objectPutCommand, record.objectPutCommand)
                && Arrays.equals(expectedObjectDestinationFingerprint,
                record.expectedObjectDestinationFingerprint)
                && objectEffect.equals(record.objectEffect)
                && Objects.equals(objectTerminal, record.objectTerminal)
                && Arrays.equals(ipfsPinCommand, record.ipfsPinCommand)
                && Arrays.equals(expectedIpfsTargetFingerprint,
                record.expectedIpfsTargetFingerprint)
                && ipfsEffect.equals(record.ipfsEffect)
                && Objects.equals(ipfsTerminal, record.ipfsTerminal)
                && kafkaTarget.equals(record.kafkaTarget)
                && kafkaTopic.equals(record.kafkaTopic)
                && Arrays.equals(expectedKafkaDestinationFingerprint,
                record.expectedKafkaDestinationFingerprint)
                && Arrays.equals(notifyMessageId, record.notifyMessageId)
                && Objects.equals(notificationEffect, record.notificationEffect)
                && Objects.equals(notificationTerminal, record.notificationTerminal);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(evidenceId, businessVersion, objectEffect, objectTerminal,
                ipfsEffect, ipfsTerminal, kafkaTarget, kafkaTopic, notificationEffect,
                notificationTerminal);
        result = 31 * result + Arrays.hashCode(ownerPublicKey);
        result = 31 * result + Arrays.hashCode(submitMessageId);
        result = 31 * result + Arrays.hashCode(objectPutCommand);
        result = 31 * result + Arrays.hashCode(expectedObjectDestinationFingerprint);
        result = 31 * result + Arrays.hashCode(ipfsPinCommand);
        result = 31 * result + Arrays.hashCode(expectedIpfsTargetFingerprint);
        result = 31 * result + Arrays.hashCode(expectedKafkaDestinationFingerprint);
        return 31 * result + Arrays.hashCode(notifyMessageId);
    }
}
