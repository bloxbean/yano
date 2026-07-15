package com.bloxbean.cardano.yano.appchain.integration.detail;

import com.bloxbean.cardano.yano.appchain.integration.internal.CanonicalCbor;

/** Stable S3-compatible retention semantics used by object detail v1. */
public enum ObjectRetentionMode {
    /** No provider retention lock; a deadline is forbidden. */
    NONE(0, false),
    /** Governance retention lock; a deadline is mandatory. */
    GOVERNANCE(1, true),
    /** Compliance retention lock; a deadline is mandatory. */
    COMPLIANCE(2, true);

    private final int code;
    private final boolean retainUntilRequired;

    ObjectRetentionMode(int code, boolean retainUntilRequired) {
        this.code = code;
        this.retainUntilRequired = retainUntilRequired;
    }

    /**
     * Returns the compact detail wire code.
     *
     * @return the non-negative mode code
     */
    public int code() {
        return code;
    }

    /**
     * Reports whether this mode requires a retention deadline.
     *
     * @return {@code true} when {@code retainUntilEpochMillis} is mandatory
     */
    public boolean retainUntilRequired() {
        return retainUntilRequired;
    }

    /**
     * Resolves a detail wire code.
     *
     * @param code the unsigned mode code
     * @return the matching retention mode
     */
    public static ObjectRetentionMode fromCode(long code) {
        for (ObjectRetentionMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        throw CanonicalCbor.malformed();
    }
}
