package com.bloxbean.cardano.yano.runtime.sync.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered fail-closed block-body validation pipeline.
 */
public final class BodyValidationPipeline implements BodyValidator {
    private final List<BodyValidator> validators;

    private BodyValidationPipeline(List<BodyValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    public static BodyValidator of(List<BodyValidator> validators) {
        if (validators == null || validators.isEmpty()) {
            return BodyValidator.none();
        }
        List<BodyValidator> copy = new ArrayList<>();
        for (BodyValidator validator : validators) {
            if (validator != null) {
                copy.add(validator);
            }
        }
        if (copy.isEmpty()) {
            return BodyValidator.none();
        }
        return new BodyValidationPipeline(copy);
    }

    @Override
    public BodyValidationResult validate(BodyValidationContext context) {
        Objects.requireNonNull(context, "context");
        for (BodyValidator validator : validators) {
            BodyValidationResult result;
            try {
                result = validator.validate(context);
            } catch (RuntimeException e) {
                return BodyValidationResult.rejected(
                        validator.getClass().getName(),
                        "validator-error",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            }
            if (result == null) {
                return BodyValidationResult.rejected(
                        validator.getClass().getName(),
                        "validator-error",
                        "validator returned null");
            }
            if (!result.accepted()) {
                return result;
            }
        }
        return BodyValidationResult.accepted("pipeline");
    }
}
