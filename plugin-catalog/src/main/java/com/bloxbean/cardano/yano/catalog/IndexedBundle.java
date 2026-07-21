package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;

import java.util.Objects;

/**
 * Build/runtime-owned artifact evidence paired with a validated manifest.
 *
 * @param manifest validated bundle manifest snapshot
 * @param digest lowercase SHA-256 artifact or deployment-closure digest
 * @param digestMode byte-evidence scope and digest strategy
 */
public record IndexedBundle(
        BundleManifest manifest,
        String digest,
        PluginDigestMode digestMode
) {
    /** Validates and creates indexed bundle evidence. */
    public IndexedBundle {
        manifest = Objects.requireNonNull(manifest, "manifest");
        digest = CatalogDigests.requireSha256(digest);
        digestMode = Objects.requireNonNull(digestMode, "digestMode");
        if (digestMode == PluginDigestMode.LEGACY_CLASS) {
            throw new IllegalArgumentException(
                    "manifested bundle digestMode must be JAR, ARTIFACT_TREE, "
                            + "or ARTIFACT_CLOSURE");
        }
    }
}
