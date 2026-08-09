package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.List;
import java.util.Objects;

/** Secret-free catalog entry published by the host for one active UI contribution. */
public record UiExtensionCatalogEntry(
        String bundleId,
        String extensionId,
        String title,
        String mountPoint,
        int uiApiVersion,
        String assetsDigest,
        String entrypointUrl,
        List<String> requiredCapabilities,
        List<String> permissions
) {
    public UiExtensionCatalogEntry {
        bundleId = Objects.requireNonNull(bundleId, "bundleId");
        extensionId = Objects.requireNonNull(extensionId, "extensionId");
        title = Objects.requireNonNull(title, "title");
        mountPoint = Objects.requireNonNull(mountPoint, "mountPoint");
        assetsDigest = Objects.requireNonNull(assetsDigest, "assetsDigest");
        entrypointUrl = Objects.requireNonNull(entrypointUrl, "entrypointUrl");
        requiredCapabilities = List.copyOf(requiredCapabilities);
        permissions = List.copyOf(permissions);
    }
}
