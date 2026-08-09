package com.bloxbean.cardano.yano.api.plugin.ui;

import java.util.Arrays;
import java.util.Objects;

/** One already-validated, immutable asset returned by the host gateway. */
public record UiExtensionAssetResponse(String mediaType, String sha256Hex, byte[] bytes) {
    public UiExtensionAssetResponse {
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        sha256Hex = Objects.requireNonNull(sha256Hex, "sha256Hex");
        bytes = Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length);
    }

    @Override public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }
}
