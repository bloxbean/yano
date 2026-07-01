package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;
import java.util.Set;

/**
 * Upstream header/body validation level.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamValidationConfig {
    private static final Set<String> SUPPORTED_HEADER_LEVELS = Set.of("none", "structural", "header-signature");
    private static final Set<String> SUPPORTED_BODY_LEVELS = Set.of("none");

    @Builder.Default
    private String level = "none";
    @Builder.Default
    private String bodyLevel = "none";

    public void validate() {
        String normalized = normalizedLevel();
        if (!SUPPORTED_HEADER_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.validation.level: " + level
                    + " (supported now: none, structural, header-signature)");
        }
        String normalizedBody = normalizedBodyLevel();
        if (!SUPPORTED_BODY_LEVELS.contains(normalizedBody)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.validation.body-level: " + bodyLevel
                    + " (supported now: none)");
        }
    }

    public String normalizedLevel() {
        return level == null || level.isBlank()
                ? "none"
                : level.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedBodyLevel() {
        return bodyLevel == null || bodyLevel.isBlank()
                ? "none"
                : bodyLevel.trim().toLowerCase(Locale.ROOT);
    }

    public boolean producesHeaderEvidence() {
        return !"none".equals(normalizedLevel());
    }
}
