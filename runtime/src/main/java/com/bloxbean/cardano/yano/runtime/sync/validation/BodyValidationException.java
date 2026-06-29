package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Raised when a fetched block body fails synchronous validation.
 */
public class BodyValidationException extends RuntimeException {
    private final BodyValidationResult result;

    public BodyValidationException(BodyValidationResult result) {
        super(result != null ? result.reason() : "body validation failed");
        this.result = result;
    }

    public BodyValidationResult result() {
        return result;
    }
}
