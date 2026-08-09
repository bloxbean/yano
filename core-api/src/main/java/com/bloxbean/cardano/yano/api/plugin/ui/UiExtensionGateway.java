package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.List;
import java.util.Optional;

/** Host port for the active, content-verified UI catalog and asset namespace. */
public interface UiExtensionGateway {
    List<UiExtensionCatalogEntry> catalog();

    Optional<UiExtensionAssetResponse> asset(
            String bundleId,
            String assetsDigest,
            String normalizedPath
    );

    static UiExtensionGateway empty() {
        return Empty.INSTANCE;
    }

    enum Empty implements UiExtensionGateway {
        INSTANCE;

        @Override public List<UiExtensionCatalogEntry> catalog() { return List.of(); }

        @Override
        public Optional<UiExtensionAssetResponse> asset(
                String bundleId, String assetsDigest, String normalizedPath) {
            return Optional.empty();
        }
    }
}
