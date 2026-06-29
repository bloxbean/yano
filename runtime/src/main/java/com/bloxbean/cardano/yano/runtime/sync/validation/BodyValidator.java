package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Pluggable synchronous block-body validation boundary.
 */
@FunctionalInterface
public interface BodyValidator {
    BodyValidationResult validate(BodyValidationContext context);

    static BodyValidator none() {
        return context -> BodyValidationResult.accepted("none");
    }
}
