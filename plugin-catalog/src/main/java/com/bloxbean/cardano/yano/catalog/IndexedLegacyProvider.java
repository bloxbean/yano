package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;

import java.util.Objects;

/**
 * Service entry from an artifact that has not adopted a bundle manifest.
 *
 * @param kind supported provider kind
 * @param provider fully qualified ASCII binary provider class name
 * @param digest lowercase SHA-256 evidence digest
 * @param digestMode digest strategy used for the evidence
 */
public record IndexedLegacyProvider(
        ContributionKind kind,
        String provider,
        String digest,
        PluginDigestMode digestMode
) {
    /** Validates and creates indexed legacy-provider evidence. */
    public IndexedLegacyProvider {
        kind = Objects.requireNonNull(kind, "kind");
        provider = CatalogNames.providerClass(provider);
        digest = CatalogDigests.requireSha256(digest);
        digestMode = Objects.requireNonNull(digestMode, "digestMode");
        if (digestMode == PluginDigestMode.ARTIFACT_CLOSURE) {
            throw new IllegalArgumentException(
                    "legacy provider digestMode must be JAR, ARTIFACT_TREE, or LEGACY_CLASS");
        }
    }
}
