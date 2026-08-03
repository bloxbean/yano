package com.bloxbean.cardano.yano.api.appchain.state;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Canonical reserved values used by ADR-025 state backends. */
public final class StateCommitmentValues {
    private static final byte[] CLASSIC_JMT_TOMBSTONE =
            "yano:jmt:logical-tombstone:v1\0".getBytes(StandardCharsets.US_ASCII);

    private StateCommitmentValues() {
    }

    /** Value committed by classic JMT when {@code AppStateWriter.delete()} is used. */
    public static byte[] classicJmtTombstone() {
        return CLASSIC_JMT_TOMBSTONE.clone();
    }

    public static boolean isClassicJmtTombstone(byte[] value) {
        return value != null && Arrays.equals(CLASSIC_JMT_TOMBSTONE, value);
    }
}
