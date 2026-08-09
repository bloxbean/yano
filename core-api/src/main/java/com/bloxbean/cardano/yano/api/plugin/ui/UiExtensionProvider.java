package com.bloxbean.cardano.yano.api.plugin.ui;

import java.io.IOException;
import java.io.InputStream;

/** ServiceLoader SPI for one validated, bundle-owned UI extension. */
public interface UiExtensionProvider {
    String id();

    UiExtensionDescriptor descriptor();

    UiExtensionAssetManifest assets();

    /** Opens a declared asset. The caller closes the returned stream. */
    InputStream openAsset(String normalizedPath) throws IOException;
}
