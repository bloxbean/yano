package com.bloxbean.cardano.yano.api.plugin;

import java.util.List;
import java.util.Objects;

/** Secret-free inventory entry for one discovered plugin bundle. */
public record PluginBundleInfo(
        String id,
        String version,
        boolean selected,
        PluginSelectionStatus selectionStatus,
        boolean legacy,
        PluginSourceCategory source,
        String digest,
        PluginDigestMode digestMode,
        List<String> dependencies,
        List<PluginContributionInfo> contributions
) {
    public PluginBundleInfo {
        selectionStatus = Objects.requireNonNull(selectionStatus, "selectionStatus");
        if (selected != (selectionStatus == PluginSelectionStatus.SELECTED)) {
            throw new IllegalArgumentException(
                    "selected must agree with selectionStatus");
        }
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }
}
