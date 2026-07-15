package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Small mutable pointer from an evidence id to its latest immutable version. */
public record EvidenceHeadV1(String evidenceId, byte[] ownerPublicKey, long latestVersion) {
    private static final int MAX_ENCODED_BYTES = 128;

    /** Validates the identifier, owner key, and positive latest version. */
    public EvidenceHeadV1 {
        evidenceId = EvidenceValidation.evidenceId(evidenceId);
        ownerPublicKey = EvidenceValidation.exactBytes(ownerPublicKey,
                EvidenceContract.HASH_BYTES);
        latestVersion = EvidenceValidation.positiveVersion(latestVersion);
    }

    @Override
    public byte[] ownerPublicKey() {
        return ownerPublicKey.clone();
    }

    /** Encodes {@code [1, id, ownerPublicKey32, latestVersion]}. */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(EvidenceContract.SCHEMA_VERSION));
        root.add(new UnicodeString(evidenceId));
        root.add(new ByteString(ownerPublicKey));
        root.add(new UnsignedInteger(latestVersion));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, MAX_ENCODED_BYTES);
        return encoded;
    }

    /** Decodes one strict canonical head. */
    public static EvidenceHeadV1 decode(byte[] encoded) {
        Array root = CanonicalCbor.decodeArray(encoded, MAX_ENCODED_BYTES, 4);
        List<DataItem> fields = CanonicalCbor.items(root);
        return new EvidenceHeadV1(
                CanonicalCbor.text(fields.get(1)),
                CanonicalCbor.bytes(fields.get(2)),
                CanonicalCbor.uint(fields.get(3)));
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceHeadV1 head
                && latestVersion == head.latestVersion
                && evidenceId.equals(head.evidenceId)
                && Arrays.equals(ownerPublicKey, head.ownerPublicKey);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(evidenceId, latestVersion) + Arrays.hashCode(ownerPublicKey);
    }
}
