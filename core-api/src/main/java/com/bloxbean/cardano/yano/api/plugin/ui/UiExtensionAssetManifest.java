package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validated immutable asset inventory for one UI extension. */
public record UiExtensionAssetManifest(int schemaVersion, List<UiExtensionAsset> assets) {
    public UiExtensionAssetManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported UI asset manifest version");
        assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
        if (assets.isEmpty() || assets.size() > UiExtensionValidation.MAX_ASSETS) {
            throw new IllegalArgumentException("UI asset count is outside the allowed range");
        }
        Set<String> paths = new HashSet<>();
        long total = 0;
        for (UiExtensionAsset asset : assets) {
            if (!paths.add(asset.path())) throw new IllegalArgumentException("duplicate UI asset path");
            total = Math.addExact(total, asset.size());
        }
        if (total > UiExtensionValidation.MAX_TOTAL_ASSET_BYTES) {
            throw new IllegalArgumentException("UI extension exceeds its total asset budget");
        }
    }

    public UiExtensionAsset require(String path) {
        String normalized = UiExtensionValidation.assetPath(path, "path");
        return assets.stream().filter(asset -> asset.path().equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("undeclared UI asset"));
    }
}
