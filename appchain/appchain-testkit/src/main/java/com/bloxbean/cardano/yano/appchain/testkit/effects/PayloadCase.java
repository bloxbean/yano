package com.bloxbean.cardano.yano.appchain.testkit.effects;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;

import java.util.Arrays;
import java.util.Objects;

/**
 * One malformed or boundary payload and its expected safe definitive code.
 *
 * @param name the unique dynamic-test display name
 * @param payload the malformed or boundary payload; defensively copied
 * @param expectedCode the normalized definitive failure expected from the executor
 */
public record PayloadCase(String name, byte[] payload, ConnectorErrorCode expectedCode) {
    /** Validates the display name and defensively snapshots the payload. */
    public PayloadCase {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        payload = payload != null ? payload.clone() : new byte[0];
        Objects.requireNonNull(expectedCode, "expectedCode");
    }

    /**
     * Returns a defensive copy of the test payload.
     *
     * @return the payload bytes
     */
    @Override public byte[] payload() { return payload.clone(); }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PayloadCase payloadCase
                && name.equals(payloadCase.name)
                && Arrays.equals(payload, payloadCase.payload)
                && expectedCode == payloadCase.expectedCode;
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(name, expectedCode) + Arrays.hashCode(payload);
    }
}
