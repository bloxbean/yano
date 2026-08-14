package com.bloxbean.cardano.yano.api.plugin;

/** Inventory entry for one typed contribution owned by a bundle. */
public record PluginContributionInfo(
        String kind,
        String name,
        String providerClass,
        PluginTrustTier trustTier
) {
}
