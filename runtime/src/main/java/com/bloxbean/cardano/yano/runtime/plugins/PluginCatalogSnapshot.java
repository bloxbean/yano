package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginCatalogView;

import java.util.List;
import java.util.Objects;

/** Published only after the complete catalog has validated. */
record PluginCatalogSnapshot(
        int pluginApiMajor,
        int pluginApiLevel,
        String fingerprint,
        List<PluginBundleInfo> bundles,
        List<String> selectedBundleOrder
) implements PluginCatalogView {
    PluginCatalogSnapshot {
        if (pluginApiMajor <= 0) {
            throw new IllegalArgumentException("pluginApiMajor must be positive");
        }
        if (pluginApiLevel <= 0) {
            throw new IllegalArgumentException("pluginApiLevel must be positive");
        }
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        bundles = List.copyOf(bundles);
        selectedBundleOrder = List.copyOf(selectedBundleOrder);
    }
}
