package com.bloxbean.cardano.yano.api.plugin;

import java.util.List;

/** Immutable, secret-free view of the validated plugin catalog. */
public interface PluginCatalogView {

    /** Plugin API compatibility major used for catalog validation. */
    int pluginApiMajor();

    /** Global additive plugin API level used for catalog validation. */
    int pluginApiLevel();

    /** Canonical selected-catalog fingerprint, prefixed with {@code sha256:}. */
    String fingerprint();

    /** All discovered bundles in deterministic id order. */
    List<PluginBundleInfo> bundles();

    /** Selected bundle ids in deterministic dependency-first order. */
    List<String> selectedBundleOrder();
}
