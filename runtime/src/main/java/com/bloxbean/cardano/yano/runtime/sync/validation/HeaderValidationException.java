package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Raised when a canonical upstream header fails validation.
 */
public class HeaderValidationException extends RuntimeException {
    private final HeaderValidationResult result;

    public HeaderValidationException(HeaderValidationResult result) {
        super(result != null ? result.reason() : "header validation failed");
        this.result = result;
    }

    public HeaderValidationResult result() {
        return result;
    }
}
