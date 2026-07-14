package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginCatalogView;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable result of resource-only plugin catalog inspection. */
public record PluginCatalogInspection(
        int pluginApiMajor,
        int pluginApiLevel,
        String fingerprint,
        List<PluginBundleInfo> bundles,
        List<String> selectedBundleOrder
) implements PluginCatalogView {
    /** Validates and defensively copies an inspection result. */
    public PluginCatalogInspection {
        if (pluginApiMajor <= 0) {
            throw new IllegalArgumentException("pluginApiMajor must be positive");
        }
        if (pluginApiLevel <= 0) {
            throw new IllegalArgumentException("pluginApiLevel must be positive");
        }
        fingerprint = CatalogDigests.requireSha256(fingerprint);
        bundles = List.copyOf(Objects.requireNonNull(bundles, "bundles"));
        selectedBundleOrder = List.copyOf(Objects.requireNonNull(
                selectedBundleOrder, "selectedBundleOrder"));

        Set<String> discovered = new HashSet<>();
        Set<String> selected = new HashSet<>();
        String previous = null;
        for (PluginBundleInfo bundle : bundles) {
            Objects.requireNonNull(bundle, "bundles must not contain null");
            CatalogValidation.bundleId(bundle.id(), "bundles[].id");
            if (!discovered.add(bundle.id())) {
                throw new IllegalArgumentException("bundles must not contain duplicate ids");
            }
            if (previous != null && previous.compareTo(bundle.id()) >= 0) {
                throw new IllegalArgumentException("bundles must be ordered by id");
            }
            previous = bundle.id();
            if (bundle.selected()) {
                selected.add(bundle.id());
            }
        }
        Set<String> ordered = new HashSet<>();
        for (String id : selectedBundleOrder) {
            CatalogValidation.bundleId(id, "selectedBundleOrder[]");
            if (!ordered.add(id)) {
                throw new IllegalArgumentException(
                        "selectedBundleOrder must not contain duplicate ids");
            }
        }
        if (!ordered.equals(selected)) {
            throw new IllegalArgumentException(
                    "selectedBundleOrder must contain every selected bundle exactly once");
        }
    }
}
