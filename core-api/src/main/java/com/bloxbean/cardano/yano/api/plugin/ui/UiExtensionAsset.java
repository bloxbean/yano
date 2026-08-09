package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One immutable, content-addressed resource packaged below {@code META-INF/yano/ui/}. */
public record UiExtensionAsset(String path, String mediaType, long size, String sha256Hex) {
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> MEDIA_TYPES = Set.of(
            "text/html", "text/css", "application/javascript", "application/json",
            "image/svg+xml", "image/png", "image/webp");

    public UiExtensionAsset {
        path = UiExtensionValidation.assetPath(path, "path");
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        if (!MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("unsupported UI asset media type");
        }
        if (size < 0 || size > UiExtensionValidation.MAX_ASSET_BYTES) {
            throw new IllegalArgumentException("UI asset size is outside the allowed range");
        }
        if (sha256Hex == null || !DIGEST.matcher(sha256Hex).matches()) {
            throw new IllegalArgumentException("UI asset digest must be lowercase SHA-256 hex");
        }
    }
}
