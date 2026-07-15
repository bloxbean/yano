package com.bloxbean.cardano.yano.appchain.examples.evidence.query;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.List;

/** Committed lookup request; business version zero means the latest head version. */
public record EvidenceGetRequestV1(String evidenceId, long businessVersion) {
    private static final int MAX_ENCODED_BYTES = 128;

    /** Validates the identifier and a non-negative requested version. */
    public EvidenceGetRequestV1 {
        evidenceId = EvidenceValidation.evidenceId(evidenceId);
        if (businessVersion < 0) {
            throw EvidenceValidation.invalid();
        }
    }

    /** Returns whether the query should resolve the version from the head value. */
    public boolean latest() {
        return businessVersion == 0;
    }

    /** Encodes {@code [1, id, versionOrZero]}. */
    public byte[] encode() {
        Array root = new Array();
        root.add(new UnsignedInteger(EvidenceContract.SCHEMA_VERSION));
        root.add(new UnicodeString(evidenceId));
        root.add(new UnsignedInteger(businessVersion));
        byte[] encoded = CanonicalCbor.encode(root);
        CanonicalCbor.requireEncodedBound(encoded, MAX_ENCODED_BYTES);
        return encoded;
    }

    /** Decodes one strict canonical lookup request. */
    public static EvidenceGetRequestV1 decode(byte[] encoded) {
        Array root = CanonicalCbor.decodeArray(encoded, MAX_ENCODED_BYTES, 3);
        List<DataItem> fields = CanonicalCbor.items(root);
        return new EvidenceGetRequestV1(
                CanonicalCbor.text(fields.get(1)), CanonicalCbor.uint(fields.get(2)));
    }
}
