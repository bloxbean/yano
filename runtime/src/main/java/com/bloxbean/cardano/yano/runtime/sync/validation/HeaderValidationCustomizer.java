package com.bloxbean.cardano.yano.runtime.sync.validation;

/**
 * Extension point for adding or replacing header-validation stages.
 */
@FunctionalInterface
public interface HeaderValidationCustomizer {
    void customize(HeaderValidationPipeline.Builder builder);
}
