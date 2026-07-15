package com.bloxbean.cardano.yano.appchain.objectstore.s3.internal;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;

import java.util.Objects;

/**
 * Sanitized provider failure crossing the SDK boundary.
 *
 * <p>Vendor messages and request objects are deliberately not retained: they
 * can contain endpoints, signed headers, object keys, or credential-derived
 * material. The adapter records only one frozen connector error code.</p>
 */
public final class ObjectStoreException extends RuntimeException {
    /** Normalized classification safe to expose outside the provider adapter. */
    private final ConnectorErrorCode code;

    /**
     * Creates a failure containing only its normalized public classification.
     * @param code frozen non-secret connector error code
     */
    public ObjectStoreException(ConnectorErrorCode code) {
        // Disable suppressed failures so try-with-resources cannot attach a
        // raw SDK/transport exception containing endpoint or request details.
        super(Objects.requireNonNull(code, "code").wireCode(), null, false, true);
        this.code = code;
    }

    /**
     * Returns the normalized connector classification.
     *
     * @return frozen error code
     */
    public ConnectorErrorCode code() {
        return code;
    }
}
