package com.bloxbean.cardano.yano.api.plugin.operations;

/** Bounded aggregate counts in one cached operations snapshot. */
public record PluginOperationsTotals(
        int discoveredBundles,
        int selectedBundles,
        int activeBundles,
        int degradedBundles,
        int failedBundles,
        int contributions,
        int observedContributions,
        int observedActiveContributions,
        int staleSources,
        int activeSamples
) {
    public PluginOperationsTotals {
        PluginOperationsValidation.nonNegative(discoveredBundles, "discoveredBundles");
        PluginOperationsValidation.nonNegative(selectedBundles, "selectedBundles");
        PluginOperationsValidation.nonNegative(activeBundles, "activeBundles");
        PluginOperationsValidation.nonNegative(degradedBundles, "degradedBundles");
        PluginOperationsValidation.nonNegative(failedBundles, "failedBundles");
        PluginOperationsValidation.nonNegative(contributions, "contributions");
        PluginOperationsValidation.nonNegative(
                observedContributions, "observedContributions");
        PluginOperationsValidation.nonNegative(
                observedActiveContributions, "observedActiveContributions");
        PluginOperationsValidation.nonNegative(staleSources, "staleSources");
        PluginOperationsValidation.nonNegative(activeSamples, "activeSamples");
        if (selectedBundles > discoveredBundles
                || activeBundles > selectedBundles
                || degradedBundles > selectedBundles
                || failedBundles > selectedBundles
                || observedContributions > contributions
                || observedActiveContributions > observedContributions) {
            throw new IllegalArgumentException("operations totals contain inconsistent counts");
        }
    }

    public static PluginOperationsTotals empty() {
        return new PluginOperationsTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
