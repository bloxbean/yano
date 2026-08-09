package com.bloxbean.cardano.yano.api.plugin.ui;

/** ServiceLoader SPI for one validated, bundle-owned UI extension. */
public interface UiExtensionProvider {
    String id();

    UiExtensionDescriptor descriptor();

    UiExtensionAssetManifest assets();

    /** Reads one declared asset through the plugin callback lifetime fence. */
    byte[] assetBytes(String normalizedPath);
}
