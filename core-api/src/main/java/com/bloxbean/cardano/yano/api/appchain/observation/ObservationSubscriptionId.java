package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Arrays;

/** Immutable developer-facing handle for a canonical 32-byte subscription id. */
public record ObservationSubscriptionId(byte[] bytes) {
    public ObservationSubscriptionId {
        bytes = ObservationCbor.fixed(bytes, 32, "subscription id");
    }

    @Override public byte[] bytes() { return bytes.clone(); }

    @Override public boolean equals(Object other) {
        return other instanceof ObservationSubscriptionId id && Arrays.equals(bytes, id.bytes);
    }

    @Override public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
