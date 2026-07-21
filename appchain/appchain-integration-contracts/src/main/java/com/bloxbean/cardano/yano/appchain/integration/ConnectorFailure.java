package com.bloxbean.cardano.yano.appchain.integration;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Helpers for returning stable, non-secret connector failures. */
public final class ConnectorFailure {
    private ConnectorFailure() {
    }

    /**
     * Returns the bounded public failure reason for an effect result.
     *
     * @param code the normalized failure code
     * @return the stable wire code
     */
    public static String reason(ConnectorErrorCode code) {
        String value = Objects.requireNonNull(code, "code").wireCode();
        if (value.getBytes(StandardCharsets.US_ASCII).length > ConnectorLimits.MAX_FAILURE_CODE_BYTES) {
            throw new IllegalStateException("connector failure code exceeds wire bound");
        }
        return value;
    }

    /**
     * Reports whether the runtime may make another bounded attempt.
     *
     * @param code the normalized failure code
     * @return {@code true} when bounded retry is permitted
     */
    public static boolean retryable(ConnectorErrorCode code) {
        return Objects.requireNonNull(code, "code").disposition().retryable();
    }
}
