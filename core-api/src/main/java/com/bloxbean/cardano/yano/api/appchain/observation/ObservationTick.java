package com.bloxbean.cardano.yano.api.appchain.observation;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;

import java.util.List;
import java.util.Objects;

/** Canonical member wake hint for a locally stable verified L1 slot. */
public record ObservationTick(int version, ObservationAnchorType anchorType, long anchorValue) {
    public static final int MAX_ENCODED_BYTES = 32;

    public ObservationTick {
        if (version != ObservationCbor.VERSION || anchorValue < 1) {
            throw new IllegalArgumentException("invalid observation tick fields");
        }
        anchorType = Objects.requireNonNull(anchorType, "anchorType");
        if (anchorType != ObservationAnchorType.VERIFIED_L1_SLOT) {
            throw new IllegalArgumentException("v1 ticks support VERIFIED_L1_SLOT only");
        }
    }

    public byte[] encode() {
        Array result = ObservationCbor.array();
        ObservationCbor.uint(result, version);
        ObservationCbor.uint(result, anchorType.code());
        ObservationCbor.uint(result, anchorValue);
        return ObservationCbor.encode(result);
    }

    public static ObservationTick decode(byte[] bytes) {
        try {
            List<DataItem> fields = ObservationCbor.decode(bytes, MAX_ENCODED_BYTES,
                    8, 4, 0, 3, "tick");
            ObservationTick value = new ObservationTick(
                    ObservationCbor.intValue(fields.get(0)),
                    ObservationAnchorType.fromCode(ObservationCbor.intValue(fields.get(1))),
                    ObservationCbor.longValue(fields.get(2)));
            ObservationCbor.canonical(bytes, value.encode(), "tick");
            return value;
        } catch (RuntimeException malformed) {
            throw ObservationCbor.invalid("tick");
        }
    }
}
