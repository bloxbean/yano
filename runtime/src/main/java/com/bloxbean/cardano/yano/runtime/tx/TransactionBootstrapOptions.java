package com.bloxbean.cardano.yano.runtime.tx;

/**
 * Framework-neutral transaction bootstrap options.
 */
public record TransactionBootstrapOptions(boolean enabled,
                                          boolean effectiveEpochParamsTrackingEnabled,
                                          boolean supplementaryRulesEnabled,
                                          String scriptEvaluator) {
    public static TransactionBootstrapOptions disabled() {
        return new TransactionBootstrapOptions(false, false, false, "scalus");
    }

    public static TransactionBootstrapOptions of(boolean enabled,
                                                 boolean effectiveEpochParamsTrackingEnabled,
                                                 boolean supplementaryRulesEnabled,
                                                 String scriptEvaluator) {
        if (!enabled) {
            return disabled();
        }
        return enabled(effectiveEpochParamsTrackingEnabled, supplementaryRulesEnabled, scriptEvaluator);
    }

    public static TransactionBootstrapOptions enabled(boolean effectiveEpochParamsTrackingEnabled,
                                                      boolean supplementaryRulesEnabled,
                                                      String scriptEvaluator) {
        return new TransactionBootstrapOptions(
                true,
                effectiveEpochParamsTrackingEnabled,
                supplementaryRulesEnabled,
                scriptEvaluator == null || scriptEvaluator.isBlank() ? "scalus" : scriptEvaluator);
    }
}
