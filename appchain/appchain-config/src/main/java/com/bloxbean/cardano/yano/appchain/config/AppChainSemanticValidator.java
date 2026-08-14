package com.bloxbean.cardano.yano.appchain.config;

import java.util.List;

/**
 * Programmatic semantic-validation extension point. Untrusted plugin archives
 * continue to contribute data-only metadata; hosts explicitly register code validators.
 */
public interface AppChainSemanticValidator {
    String id();

    List<ValidationDiagnostic> validate(AppChainValidationContext context);
}
