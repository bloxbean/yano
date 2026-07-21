package com.bloxbean.cardano.yano.appchain.ipfs.internal;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorErrorCode;

import java.util.Objects;

/**
 * Sanitized IPFS provider failure.
 *
 * <p>The exception intentionally retains no response body, endpoint, token,
 * request object, provider message, or transport cause. Only the frozen
 * connector error code may cross the adapter boundary.</p>
 */
public final class IpfsProviderException extends RuntimeException {
    /** Bounded failure classification safe to cross the adapter boundary. */
    private final ConnectorErrorCode code;

    /**
     * Creates a sanitized failure.
     *
     * @param code bounded machine-readable connector failure
     */
    public IpfsProviderException(ConnectorErrorCode code) {
        super(Objects.requireNonNull(code, "code").wireCode(), null, false, true);
        this.code = code;
    }

    /**
     * Returns the normalized failure classification.
     *
     * @return the frozen connector error code
     */
    public ConnectorErrorCode code() {
        return code;
    }
}
