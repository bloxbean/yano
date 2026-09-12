package org.yanoproject.runtime.sync.validation;

/**
 * Single named header-validation stage.
 */
@FunctionalInterface
public interface HeaderStageValidator {
    HeaderValidationResult validate(HeaderValidationContext context);
}
