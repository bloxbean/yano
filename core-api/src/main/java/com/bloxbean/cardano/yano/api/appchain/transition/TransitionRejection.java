package com.bloxbean.cardano.yano.api.appchain.transition;

import java.util.Objects;

/** Stable, bounded reason returned when a capability cannot construct a plan. */
public record TransitionRejection(String code, String detail) {
    public TransitionRejection {
        code = requireBounded(code, "code", 64);
        detail = detail != null ? detail : "";
        if (detail.length() > 256) {
            throw new IllegalArgumentException("detail must contain at most 256 characters");
        }
    }

    private static String requireBounded(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " must contain 1.." + max + " characters");
        }
        return value;
    }
}
