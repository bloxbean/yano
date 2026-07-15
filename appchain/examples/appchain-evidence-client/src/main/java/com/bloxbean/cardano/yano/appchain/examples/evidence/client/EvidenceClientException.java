package com.bloxbean.cardano.yano.appchain.examples.evidence.client;

import java.util.Objects;

/** Sanitized failure from evidence orchestration or proof verification. */
public final class EvidenceClientException extends RuntimeException {
    private final EvidenceClientError code;

    EvidenceClientException(EvidenceClientError code) {
        super(message(Objects.requireNonNull(code, "code")), null, false, false);
        this.code = code;
    }

    /** Returns the stable machine-readable failure category. */
    public EvidenceClientError code() {
        return code;
    }

    private static String message(EvidenceClientError code) {
        return switch (code) {
            case INVALID_ARGUMENT -> "Invalid evidence client argument";
            case TRANSPORT_FAILURE -> "Evidence node request failed";
            case WRONG_CHAIN -> "Evidence response chain mismatch";
            case WRONG_STATE_MACHINE -> "Evidence response state-machine mismatch";
            case RESPONSE_MISMATCH -> "Evidence response does not match the request";
            case PROOF_MISSING -> "Evidence state proof is unavailable";
            case PROOF_INVALID -> "Evidence state proof is invalid";
            case SNAPSHOT_RACE_EXHAUSTED -> "Evidence snapshot changed during verification";
        };
    }
}
