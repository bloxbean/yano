package com.bloxbean.cardano.yano.appchain.integration;

/**
 * Bounded machine-readable failures safe for status, metrics snapshots, and
 * authenticated FAILED results. Vendor messages and secrets never belong in
 * these values.
 */
public enum ConnectorErrorCode {
    /** The command or result violates the frozen wire contract. */
    INVALID_PAYLOAD("INVALID_PAYLOAD", FailureDisposition.DEFINITIVE),
    /** The encoded schema version is not supported. */
    UNSUPPORTED_VERSION("UNSUPPORTED_VERSION", FailureDisposition.DEFINITIVE),
    /** The requested connector target alias is not configured. */
    UNKNOWN_TARGET("UNKNOWN_TARGET", FailureDisposition.DEFINITIVE),
    /** The requested connector target is administratively disabled. */
    TARGET_DISABLED("TARGET_DISABLED", FailureDisposition.DEFINITIVE),
    /** The resolved target changed while the effect was open. */
    TARGET_CHANGED("TARGET_CHANGED", FailureDisposition.OPERATOR_ACTION),
    /** The configured target or retention policy rejected the operation. */
    POLICY_DENIED("POLICY_DENIED", FailureDisposition.DEFINITIVE),
    /** Required connector credentials are unavailable. */
    AUTH_UNAVAILABLE("AUTH_UNAVAILABLE", FailureDisposition.OPERATOR_ACTION),
    /** The provider applied a temporary rate limit. */
    RATE_LIMITED("RATE_LIMITED", FailureDisposition.RETRYABLE),
    /** The external provider is temporarily unavailable. */
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", FailureDisposition.RETRYABLE),
    /** The mutation may have succeeded, but its acknowledgement is unknown. */
    ACK_UNKNOWN("ACK_UNKNOWN", FailureDisposition.PROBE_REQUIRED),
    /** The configured source object cannot currently be read. */
    SOURCE_UNAVAILABLE("SOURCE_UNAVAILABLE", FailureDisposition.RETRYABLE),
    /** Source bytes do not match the command commitment. */
    SOURCE_MISMATCH("SOURCE_MISMATCH", FailureDisposition.DEFINITIVE),
    /** Content addressed by the command is not currently available. */
    CONTENT_UNAVAILABLE("CONTENT_UNAVAILABLE", FailureDisposition.RETRYABLE),
    /** Configured recovery policy proves that no allowed source can supply the content. */
    CONTENT_NOT_FOUND("CONTENT_NOT_FOUND", FailureDisposition.DEFINITIVE),
    /** Existing destination content conflicts with the command. */
    DESTINATION_CONFLICT("DESTINATION_CONFLICT", FailureDisposition.DEFINITIVE),
    /** The provider definitively rejected an otherwise valid request. */
    PROVIDER_REJECTED("PROVIDER_REJECTED", FailureDisposition.DEFINITIVE),
    /** Durable archival of connector receipt details failed. */
    DETAIL_ARCHIVE_FAILED("DETAIL_ARCHIVE_FAILED", FailureDisposition.RETRYABLE),
    /** Connector execution was interrupted by runtime shutdown. */
    SHUTDOWN("SHUTDOWN", FailureDisposition.RETRYABLE),
    /** An unexpected connector failure was normalized at the boundary. */
    INTERNAL_ERROR("INTERNAL_ERROR", FailureDisposition.RETRYABLE);

    private final String wireCode;
    private final FailureDisposition disposition;

    ConnectorErrorCode(String wireCode, FailureDisposition disposition) {
        if (!wireCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid connector error code: " + wireCode);
        }
        this.wireCode = wireCode;
        this.disposition = disposition;
    }

    /**
     * Returns the bounded machine-readable wire value.
     *
     * @return the stable uppercase wire code
     */
    public String wireCode() {
        return wireCode;
    }

    /**
     * Returns the default runtime treatment for this failure.
     *
     * @return the failure disposition
     */
    public FailureDisposition disposition() {
        return disposition;
    }

    /**
     * Resolves an exact wire value.
     *
     * @param code the wire value to resolve
     * @return the matching normalized code
     * @throws IllegalArgumentException when the value is unknown
     */
    public static ConnectorErrorCode fromWireCode(String code) {
        for (ConnectorErrorCode value : values()) {
            if (value.wireCode.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown connector error code");
    }
}
