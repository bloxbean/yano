package com.bloxbean.cardano.yano.appchain.integration;

import java.util.Objects;

/** A safe, classified contract rejection; the message is always the wire code. */
public final class ConnectorContractException extends IllegalArgumentException {
    /** The normalized code exposed to connector callers. */
    private final ConnectorErrorCode code;

    /**
     * Creates a rejection whose exception message is the stable wire code.
     *
     * @param code the normalized rejection code
     */
    public ConnectorContractException(ConnectorErrorCode code) {
        super(Objects.requireNonNull(code, "code").wireCode());
        this.code = code;
    }

    /**
     * Returns the normalized rejection code.
     *
     * @return the rejection code
     */
    public ConnectorErrorCode code() {
        return code;
    }
}
