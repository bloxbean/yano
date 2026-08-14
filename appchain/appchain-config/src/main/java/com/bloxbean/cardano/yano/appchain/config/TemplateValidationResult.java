package com.bloxbean.cardano.yano.appchain.config;

import java.util.List;

/** Result of validating one intentionally incomplete app-chain template. */
public record TemplateValidationResult(
        int appChainPropertyCount,
        int recognizedPropertyCount,
        List<ValidationDiagnostic> diagnostics) {

    public TemplateValidationResult {
        if (appChainPropertyCount < 0 || recognizedPropertyCount < 0
                || recognizedPropertyCount > appChainPropertyCount) {
            throw new IllegalArgumentException("invalid validation counts");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public long errorCount() {
        return diagnostics.stream()
                .filter(item -> item.severity() == ValidationSeverity.ERROR)
                .count();
    }

    public long warningCount() {
        return diagnostics.stream()
                .filter(item -> item.severity() == ValidationSeverity.WARNING)
                .count();
    }

    public long infoCount() {
        return diagnostics.stream()
                .filter(item -> item.severity() == ValidationSeverity.INFO)
                .count();
    }

    public boolean valid() {
        return errorCount() == 0;
    }
}
