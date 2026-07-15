package com.bloxbean.cardano.yano.appchain.examples.evidence.event;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceKeys;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceRecordV1;
import com.bloxbean.cardano.yano.appchain.examples.evidence.state.EvidenceStatus;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsCidFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsV1Policy;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.DigestAlgorithm;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutReceiptV1;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Canonical business event derived from a storage-ready record.
 *
 * <p>The event validates receipt integrity fields. A verifier that needs the
 * expected resolved destinations binds these exact receipt bytes back to the
 * proven {@code EvidenceRecordV1}; those expected fingerprints are intentionally
 * not duplicated in this event.</p>
 */
public record EvidenceAvailableEventV1(String evidenceId,
                                       long businessVersion,
                                       DigestAlgorithm digestAlgorithm,
                                       byte[] digest,
                                       long size,
                                       String objectTarget,
                                       String destinationKey,
                                       CanonicalCid cid,
                                       byte[] objectReceipt,
                                       byte[] ipfsReceipt) {
    /** Maximum canonical event-body size. */
    public static final int MAX_ENCODED_BYTES = 1_024;

    /** Validates event fields and binds receipts to the document digest, size, and CID. */
    public EvidenceAvailableEventV1 {
        evidenceId = EvidenceValidation.evidenceId(evidenceId);
        businessVersion = EvidenceValidation.positiveVersion(businessVersion);
        digestAlgorithm = Objects.requireNonNull(digestAlgorithm, "digestAlgorithm");
        digest = EvidenceValidation.exactBytes(digest, digestAlgorithm.digestBytes());
        cid = IpfsV1Policy.requireAllowed(cid);

        ObjectPutCommandV1 shape = new ObjectPutCommandV1(
                objectTarget, destinationKey, destinationKey, digestAlgorithm,
                digest, size, "application/octet-stream", null);
        objectReceipt = EvidenceValidation.boundedBytes(objectReceipt, 128);
        ipfsReceipt = EvidenceValidation.boundedBytes(ipfsReceipt, 128);
        validateReceipts(shape, cid, objectReceipt, ipfsReceipt);
    }

    @Override
    public byte[] digest() {
        return digest.clone();
    }

    @Override
    public byte[] objectReceipt() {
        return objectReceipt.clone();
    }

    @Override
    public byte[] ipfsReceipt() {
        return ipfsReceipt.clone();
    }

    /** Creates the event after validating all receipt and expected-destination bindings. */
    public static EvidenceAvailableEventV1 fromRecord(EvidenceRecordV1 record) {
        if (!EvidenceStatus.storageReady(record)) {
            throw EvidenceValidation.invalid();
        }
        ObjectPutCommandV1 object = record.objectPut();
        return new EvidenceAvailableEventV1(
                record.evidenceId(),
                record.businessVersion(),
                object.digestAlgorithm(),
                object.digest(),
                object.size(),
                object.target(),
                object.destinationKey(),
                record.ipfsPin().cid(),
                record.objectTerminal().externalRef(),
                record.ipfsTerminal().externalRef());
    }

    /** Returns the frozen {@code application/cbor} Kafka content type. */
    public String contentType() {
        return EvidenceContract.EVENT_CONTENT_TYPE;
    }

    /** Returns the deterministic Kafka key {@code idHash || uint64be(version)}. */
    public byte[] kafkaKey() {
        return EvidenceKeys.kafkaKey(evidenceId, businessVersion);
    }

    /** Encodes the exact 11-field v1 event body. */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(EvidenceContract.SCHEMA_VERSION));
        root.add(new UnicodeString(evidenceId));
        root.add(new UnsignedInteger(businessVersion));
        root.add(new UnsignedInteger(digestAlgorithm.code()));
        root.add(new ByteString(digest));
        root.add(new UnsignedInteger(size));
        root.add(new UnicodeString(objectTarget));
        root.add(new UnicodeString(destinationKey));
        root.add(new ByteString(cid.bytes()));
        root.add(new ByteString(objectReceipt));
        root.add(new ByteString(ipfsReceipt));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, MAX_ENCODED_BYTES);
        return encoded;
    }

    /** Decodes and validates one strict canonical event body. */
    public static EvidenceAvailableEventV1 decode(byte[] encoded) {
        Array root = CanonicalCbor.decodeArray(encoded, MAX_ENCODED_BYTES, 11);
        List<DataItem> fields = CanonicalCbor.items(root);
        return new EvidenceAvailableEventV1(
                CanonicalCbor.text(fields.get(1)),
                CanonicalCbor.uint(fields.get(2)),
                DigestAlgorithm.fromCode(CanonicalCbor.uint(fields.get(3))),
                CanonicalCbor.bytes(fields.get(4)),
                CanonicalCbor.uint(fields.get(5)),
                CanonicalCbor.text(fields.get(6)),
                CanonicalCbor.text(fields.get(7)),
                CanonicalCid.fromBytes(CanonicalCbor.bytes(fields.get(8))),
                CanonicalCbor.bytes(fields.get(9)),
                CanonicalCbor.bytes(fields.get(10)));
    }

    private static void validateReceipts(ObjectPutCommandV1 object,
                                         CanonicalCid cid,
                                         byte[] objectReceipt,
                                         byte[] ipfsReceipt) {
        try {
            ObjectPutReceiptV1 stored = ObjectPutReceiptV1.decode(objectReceipt);
            IpfsPinReceiptV1 pinned = IpfsPinReceiptV1.decode(ipfsReceipt);
            if (stored.size() != object.size()
                    || !Arrays.equals(stored.verifiedSha256(), object.digest())
                    || !Arrays.equals(pinned.cidFingerprint(),
                    IpfsCidFingerprint.compute(cid).bytes())) {
                throw EvidenceValidation.invalid();
            }
        } catch (RuntimeException exception) {
            throw EvidenceValidation.invalid();
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceAvailableEventV1 event
                && businessVersion == event.businessVersion
                && size == event.size
                && evidenceId.equals(event.evidenceId)
                && digestAlgorithm == event.digestAlgorithm
                && Arrays.equals(digest, event.digest)
                && objectTarget.equals(event.objectTarget)
                && destinationKey.equals(event.destinationKey)
                && cid.equals(event.cid)
                && Arrays.equals(objectReceipt, event.objectReceipt)
                && Arrays.equals(ipfsReceipt, event.ipfsReceipt);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(evidenceId, businessVersion, digestAlgorithm, size,
                objectTarget, destinationKey, cid);
        result = 31 * result + Arrays.hashCode(digest);
        result = 31 * result + Arrays.hashCode(objectReceipt);
        return 31 * result + Arrays.hashCode(ipfsReceipt);
    }
}
