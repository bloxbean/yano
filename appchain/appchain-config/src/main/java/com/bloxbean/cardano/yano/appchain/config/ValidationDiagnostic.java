package com.bloxbean.cardano.yano.appchain.config;

import java.util.Objects;

/** Stable, machine-readable offline validation diagnostic. */
public record ValidationDiagnostic(
        String code,
        ValidationSeverity severity,
        String key,
        String message) {

    public ValidationDiagnostic {
        code = requireText(code, "code");
        if (!code.matches("^[A-Z][A-Z0-9_]*$")) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        severity = Objects.requireNonNull(severity, "severity");
        key = sanitize(key == null ? "" : key, 512);
        message = sanitize(requireText(message, "message"), 2048);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String sanitize(String value, int maximumLength) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximumLength));
        value.codePoints().limit(maximumLength).forEach(codePoint ->
                result.appendCodePoint(Character.isISOControl(codePoint) ? '?' : codePoint));
        return result.toString();
    }
}
