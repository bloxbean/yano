package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginApiVersion;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Immutable selection and API policy for offline plugin catalog inspection. */
public record PluginCatalogInspectionPolicy(
        int pluginApiMajor,
        int pluginApiLevel,
        Set<String> allowList,
        Set<String> denyList
) {
    /** Validates and defensively copies an inspection policy. */
    public PluginCatalogInspectionPolicy {
        if (pluginApiMajor <= 0) {
            throw new IllegalArgumentException("pluginApiMajor must be positive");
        }
        if (pluginApiLevel <= 0) {
            throw new IllegalArgumentException("pluginApiLevel must be positive");
        }
        allowList = bundleIds(allowList, "allowList");
        denyList = bundleIds(denyList, "denyList");
    }

    /**
     * Returns the default policy for the API major and level supported by this release.
     *
     * @return select-all policy for the current API major
     */
    public static PluginCatalogInspectionPolicy current() {
        return new PluginCatalogInspectionPolicy(
                PluginApiVersion.CURRENT_MAJOR, PluginApiVersion.CURRENT_LEVEL,
                Set.of(), Set.of());
    }

    private static Set<String> bundleIds(Set<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        TreeSet<String> result = new TreeSet<>();
        for (String value : values) {
            result.add(CatalogValidation.bundleId(value, field + "[]"));
        }
        return Collections.unmodifiableSet(result);
    }
}
