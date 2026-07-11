package com.bloxbean.cardano.yano.runtime.sync.validation;

import java.util.List;

/**
 * Result from a header validation stage.
 */
public record HeaderValidationResult(boolean accepted,
                                     String level,
                                     String stage,
                                     String reason,
                                     List<String> acceptedStages) {
    public HeaderValidationResult {
        acceptedStages = acceptedStages != null ? List.copyOf(acceptedStages) : List.of();
    }

    public static HeaderValidationResult accepted(String level) {
        return new HeaderValidationResult(true, level, "accepted", null, List.of());
    }

    public static HeaderValidationResult accepted(String level, List<String> acceptedStages) {
        return new HeaderValidationResult(true, level, "accepted", null, acceptedStages);
    }

    public static HeaderValidationResult rejected(String level, String stage, String reason) {
        return new HeaderValidationResult(false, level, stage, reason, List.of());
    }

    public static HeaderValidationResult rejected(String level, String stage, String reason, List<String> acceptedStages) {
        return new HeaderValidationResult(false, level, stage, reason, acceptedStages);
    }
}
