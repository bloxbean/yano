package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Result from a header validation stage.
 */
public record HeaderValidationResult(boolean accepted, String level, String stage, String reason) {
    public static HeaderValidationResult accepted(String level) {
        return new HeaderValidationResult(true, level, "accepted", null);
    }

    public static HeaderValidationResult rejected(String level, String stage, String reason) {
        return new HeaderValidationResult(false, level, stage, reason);
    }
}
