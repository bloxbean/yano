package com.bloxbean.cardano.yano.appchain.integration;

/** Default executor behavior associated with a normalized connector failure. */
public enum FailureDisposition {
    /** The same instruction cannot succeed without changing the instruction or policy. */
    DEFINITIVE(false, false),
    /** A bounded retry may succeed without first probing for an unknown mutation. */
    RETRYABLE(true, false),
    /** The acknowledgement is unknown; probe external state before any repeated mutation. */
    PROBE_REQUIRED(true, true),
    /** Credentials/configuration need operator action; retry only through bounded parking policy. */
    OPERATOR_ACTION(true, false);

    private final boolean retryable;
    private final boolean probeRequired;

    FailureDisposition(boolean retryable, boolean probeRequired) {
        this.retryable = retryable;
        this.probeRequired = probeRequired;
    }

    /**
     * Reports whether a bounded runtime retry is allowed.
     *
     * @return {@code true} when retry is allowed
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Reports whether external state must be probed before another mutation.
     *
     * @return {@code true} when a probe is mandatory
     */
    public boolean probeRequired() {
        return probeRequired;
    }
}
