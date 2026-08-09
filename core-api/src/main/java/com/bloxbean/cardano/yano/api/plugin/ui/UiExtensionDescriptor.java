package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.List;
import java.util.Objects;

/** Framework-neutral descriptor for a sandboxed console contribution. */
public record UiExtensionDescriptor(
        int uiApiVersion,
        String extensionId,
        String title,
        UiExtensionMountPoint mountPoint,
        String entrypoint,
        List<String> requiredCapabilities,
        List<UiExtensionPermission> permissions,
        String assetsManifest
) {
    public UiExtensionDescriptor {
        if (uiApiVersion != UiExtensionValidation.UI_API_VERSION) {
            throw new IllegalArgumentException("unsupported UI API version");
        }
        extensionId = UiExtensionValidation.identifier(extensionId, "extensionId", 64);
        title = UiExtensionValidation.displayText(title, "title", 80);
        mountPoint = Objects.requireNonNull(mountPoint, "mountPoint");
        entrypoint = UiExtensionValidation.assetPath(entrypoint, "entrypoint");
        assetsManifest = UiExtensionValidation.assetPath(assetsManifest, "assetsManifest");
        requiredCapabilities = UiExtensionValidation.capabilities(requiredCapabilities);
        permissions = UiExtensionValidation.permissions(permissions);
    }
}
