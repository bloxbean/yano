package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Single named header-validation stage.
 */
@FunctionalInterface
public interface HeaderStageValidator {
    HeaderValidationResult validate(HeaderValidationContext context);
}
