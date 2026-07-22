package com.bloxbean.cardano.yano.appchain.examples.evidence.command;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.List;

/** Strict dispatcher for all v1 evidence commands. */
public final class EvidenceCommandCodec {
    private EvidenceCommandCodec() {
    }

    /** Decodes one canonical command and rejects unknown versions, opcodes, or shapes. */
    public static EvidenceCommandV1 decode(byte[] encoded) {
        try {
            Array root = CanonicalCbor.decodeArray(encoded, EvidenceContract.MAX_COMMAND_BYTES);
            List<DataItem> items = CanonicalCbor.items(root);
            if (items.size() < 2) {
                throw EvidenceValidation.invalid();
            }
            CanonicalCbor.requireVersion(items.get(0));
            EvidenceCommandOperation operation = EvidenceCommandOperation.fromCode(
                    CanonicalCbor.uint(items.get(1)));
            return switch (operation) {
                case SUBMIT -> decodeSubmit(items);
                case NOTIFY -> decodeNotify(items);
                case REPUBLISH -> decodeRepublish(items);
            };
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw EvidenceValidation.invalid();
        }
    }

    static byte[] encodeStorage(EvidenceCommandV1 command) {
        byte[] objectCommand;
        byte[] objectDestination;
        byte[] ipfsCommand;
        byte[] ipfsTarget;
        String kafkaTarget;
        String kafkaTopic;
        byte[] kafkaDestination;
        if (command instanceof SubmitEvidenceCommandV1 submit) {
            objectCommand = submit.objectPutCommand();
            objectDestination = submit.expectedObjectDestinationFingerprint();
            ipfsCommand = submit.ipfsPinCommand();
            ipfsTarget = submit.expectedIpfsTargetFingerprint();
            kafkaTarget = submit.kafkaTarget();
            kafkaTopic = submit.kafkaTopic();
            kafkaDestination = submit.expectedKafkaDestinationFingerprint();
        } else if (command instanceof RepublishEvidenceCommandV1 republish) {
            objectCommand = republish.objectPutCommand();
            objectDestination = republish.expectedObjectDestinationFingerprint();
            ipfsCommand = republish.ipfsPinCommand();
            ipfsTarget = republish.expectedIpfsTargetFingerprint();
            kafkaTarget = republish.kafkaTarget();
            kafkaTopic = republish.kafkaTopic();
            kafkaDestination = republish.expectedKafkaDestinationFingerprint();
        } else {
            throw EvidenceValidation.invalid();
        }

        Array root = base(command);
        root.add(new ByteString(objectCommand));
        root.add(new ByteString(objectDestination));
        root.add(new ByteString(ipfsCommand));
        root.add(new ByteString(ipfsTarget));
        root.add(new UnicodeString(kafkaTarget));
        root.add(new UnicodeString(kafkaTopic));
        root.add(new ByteString(kafkaDestination));
        return bounded(root);
    }

    static byte[] encodeNotify(NotifyEvidenceCommandV1 command) {
        return bounded(base(command));
    }

    private static EvidenceCommandV1 decodeSubmit(List<DataItem> items) {
        requireArity(items, 11);
        return new SubmitEvidenceCommandV1(
                CanonicalCbor.text(items.get(2)),
                CanonicalCbor.uint(items.get(3)),
                CanonicalCbor.bytes(items.get(4)),
                CanonicalCbor.bytes(items.get(5)),
                CanonicalCbor.bytes(items.get(6)),
                CanonicalCbor.bytes(items.get(7)),
                CanonicalCbor.text(items.get(8)),
                CanonicalCbor.text(items.get(9)),
                CanonicalCbor.bytes(items.get(10)));
    }

    private static EvidenceCommandV1 decodeNotify(List<DataItem> items) {
        requireArity(items, 4);
        return new NotifyEvidenceCommandV1(
                CanonicalCbor.text(items.get(2)), CanonicalCbor.uint(items.get(3)));
    }

    private static EvidenceCommandV1 decodeRepublish(List<DataItem> items) {
        requireArity(items, 11);
        return new RepublishEvidenceCommandV1(
                CanonicalCbor.text(items.get(2)),
                CanonicalCbor.uint(items.get(3)),
                CanonicalCbor.bytes(items.get(4)),
                CanonicalCbor.bytes(items.get(5)),
                CanonicalCbor.bytes(items.get(6)),
                CanonicalCbor.bytes(items.get(7)),
                CanonicalCbor.text(items.get(8)),
                CanonicalCbor.text(items.get(9)),
                CanonicalCbor.bytes(items.get(10)));
    }

    private static Array base(EvidenceCommandV1 command) {
        Array root = new Array();
        root.add(new UnsignedInteger(EvidenceContract.SCHEMA_VERSION));
        root.add(new UnsignedInteger(command.operation().code()));
        root.add(new UnicodeString(command.evidenceId()));
        root.add(new UnsignedInteger(command.businessVersion()));
        return root;
    }

    private static byte[] bounded(Array root) {
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, EvidenceContract.MAX_COMMAND_BYTES);
        return encoded;
    }

    private static void requireArity(List<DataItem> items, int arity) {
        if (items.size() != arity) {
            throw EvidenceValidation.invalid();
        }
    }
}
