package com.bloxbean.cardano.yano.appchain.examples.evidence.state;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.internal.EvidenceValidation;
import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

import java.util.List;

/** Chain position of one deterministic effect emitted for an evidence record. */
public record EvidenceEffectRef(long height, int ordinal) {
    /** Validates a positive height and non-negative ordinal. */
    public EvidenceEffectRef {
        if (height <= 0 || ordinal < 0
                || ordinal >= EvidenceContract.MAX_EFFECTS_PER_BLOCK) {
            throw EvidenceValidation.invalid();
        }
    }

    /** Encodes the reference as {@code [height, ordinal]}. */
    public byte[] encode() {
        return CanonicalCbor.encode(toDataItem());
    }

    /** Decodes one strict canonical standalone reference. */
    public static EvidenceEffectRef decode(byte[] encoded) {
        Array root = CanonicalCbor.decodeArray(encoded, 32);
        return fromDataItem(root);
    }

    Array toDataItem() {
        Array value = new Array();
        value.add(new UnsignedInteger(height));
        value.add(new UnsignedInteger(ordinal));
        return value;
    }

    static EvidenceEffectRef fromDataItem(DataItem item) {
        Array value = CanonicalCbor.array(item, 2);
        List<DataItem> fields = CanonicalCbor.items(value);
        return new EvidenceEffectRef(CanonicalCbor.uint(fields.get(0)),
                CanonicalCbor.uintInt(fields.get(1)));
    }
}
