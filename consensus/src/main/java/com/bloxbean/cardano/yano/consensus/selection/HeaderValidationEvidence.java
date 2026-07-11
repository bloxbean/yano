package com.bloxbean.cardano.yano.consensus.selection;

import java.util.List;
import java.util.Objects;

/**
 * Immutable validation evidence associated with an observed candidate header.
 */
public record HeaderValidationEvidence(
        String profile,
        boolean accepted,
        List<String> acceptedStages,
        String rejectedStage,
        String rejectionReason
) {
    public static final String PROFILE_NONE = "none";

    public HeaderValidationEvidence {
        profile = profile == null || profile.isBlank() ? PROFILE_NONE : normalize(profile);
        acceptedStages = acceptedStages != null
                ? acceptedStages.stream()
                .filter(stage -> stage != null && !stage.isBlank())
                .map(HeaderValidationEvidence::normalize)
                .distinct()
                .toList()
                : List.of();
        if (accepted) {
            rejectedStage = null;
            rejectionReason = null;
        } else {
            rejectedStage = normalize(rejectedStage);
            rejectionReason = rejectionReason == null || rejectionReason.isBlank()
                    ? "header validation rejected"
                    : rejectionReason;
        }
    }

    public static HeaderValidationEvidence none() {
        return accepted(PROFILE_NONE, List.of());
    }

    public static HeaderValidationEvidence accepted(String profile, List<String> acceptedStages) {
        return new HeaderValidationEvidence(profile, true, acceptedStages, null, null);
    }

    public static HeaderValidationEvidence rejected(String profile,
                                                    List<String> acceptedStages,
                                                    String rejectedStage,
                                                    String rejectionReason) {
        return new HeaderValidationEvidence(profile, false, acceptedStages, rejectedStage, rejectionReason);
    }

    public boolean includesStage(String stage) {
        String normalized = normalize(stage);
        return !normalized.isBlank() && acceptedStages.contains(normalized);
    }

    public boolean producesEvidence() {
        return accepted && !PROFILE_NONE.equals(profile) && !acceptedStages.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? ""
                : Objects.requireNonNull(value).trim().toLowerCase(java.util.Locale.ROOT);
    }
}
