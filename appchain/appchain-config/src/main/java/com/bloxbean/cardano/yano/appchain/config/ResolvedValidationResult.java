package com.bloxbean.cardano.yano.appchain.config;

import java.util.List;

/** Result of validating one effective, node-specific app-chain configuration. */
public record ResolvedValidationResult(
        int appChainPropertyCount,
        int recognizedPropertyCount,
        int chainCount,
        List<ValidationDiagnostic> diagnostics) {

    public ResolvedValidationResult {
        if (appChainPropertyCount < 0 || recognizedPropertyCount < 0
                || recognizedPropertyCount > appChainPropertyCount || chainCount < 0) {
            throw new IllegalArgumentException("invalid resolved validation counts");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public long errorCount() {
        return count(ValidationSeverity.ERROR);
    }

    public long warningCount() {
        return count(ValidationSeverity.WARNING);
    }

    public long infoCount() {
        return count(ValidationSeverity.INFO);
    }

    public boolean valid() {
        return errorCount() == 0;
    }

    private long count(ValidationSeverity severity) {
        return diagnostics.stream().filter(item -> item.severity() == severity).count();
    }
}
