package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Result from a synchronous block-body validation stage.
 */
public record BodyValidationResult(boolean accepted, String validatorId, String stage, String reason) {
    public static BodyValidationResult accepted(String validatorId) {
        return new BodyValidationResult(true, validatorId, "accepted", null);
    }

    public static BodyValidationResult rejected(String validatorId, String stage, String reason) {
        return new BodyValidationResult(false, validatorId, stage, reason);
    }
}
