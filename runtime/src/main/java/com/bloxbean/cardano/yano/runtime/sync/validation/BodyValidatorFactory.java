package com.bloxbean.cardano.yano.runtime.sync.validation;

import com.bloxbean.cardano.yano.api.config.UpstreamValidationConfig;

import java.util.Locale;

/**
 * Builds runtime body validators from upstream validation config.
 */
public final class BodyValidatorFactory {
    private BodyValidatorFactory() {
    }

    public static BodyValidator from(UpstreamValidationConfig config) {
        String level = config != null ? normalize(config.getBodyLevel()) : "none";
        if ("none".equals(level)) {
            return BodyValidator.none();
        }
        throw new IllegalArgumentException("Unsupported yano.upstream.validation.body-level: " + level
                + " (supported now: none)");
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "none"
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
