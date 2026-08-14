package com.bloxbean.cardano.yano.catalog;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical pre-packaging catalog input retained by uber-JAR/native builds.
 *
 * @param schemaVersion aggregate index schema version
 * @param bundles manifested bundle evidence
 * @param legacyProviders unmanifested ServiceLoader provider evidence
 */
public record PluginIndex(
        int schemaVersion,
        List<IndexedBundle> bundles,
        List<IndexedLegacyProvider> legacyProviders
) {
    /** Current supported aggregate-index schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** Classpath resource containing the canonical aggregate index. */
    public static final String RESOURCE_PATH = "META-INF/yano-plugin-index-v1.json";
    /** Maximum manifested bundles accepted in one index. */
    public static final int MAX_BUNDLES = 4_096;
    /** Maximum manifested contributions plus legacy-provider evidence in one index. */
    public static final int MAX_PROVIDERS = 32_768;
    /**
     * Compatibility name for the former legacy-only ceiling.
     *
     * @deprecated use {@link #MAX_PROVIDERS}; the ceiling now covers every
     * manifested contribution plus legacy-provider evidence together
     */
    @Deprecated
    public static final int MAX_LEGACY_PROVIDERS = MAX_PROVIDERS;

    /** Validates, canonically sorts, defensively copies, and creates an index. */
    public PluginIndex {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("plugin index schemaVersion must equal 1");
        }
        if (bundles != null && bundles.size() > MAX_BUNDLES) {
            throw new IllegalArgumentException("plugin index contains too many bundles");
        }
        if (legacyProviders != null) {
            validateProviderLimit(0, legacyProviders.size());
        }
        bundles = bundles == null ? List.of() : bundles.stream()
                .map(bundle -> Objects.requireNonNull(bundle, "plugin index bundles must not contain null"))
                .sorted(Comparator.comparing(bundle -> bundle.manifest().id()))
                .toList();
        legacyProviders = legacyProviders == null ? List.of() : legacyProviders.stream()
                .map(provider -> Objects.requireNonNull(
                        provider, "plugin index legacyProviders must not contain null"))
                .sorted(Comparator.comparing((IndexedLegacyProvider value) -> value.kind().manifestKey())
                        .thenComparing(IndexedLegacyProvider::provider))
                .toList();
        long manifestedProviders = 0;
        for (IndexedBundle bundle : bundles) {
            manifestedProviders += bundle.manifest().contributions().size();
        }
        validateProviderLimit(manifestedProviders, legacyProviders.size());
        Set<String> ids = new HashSet<>();
        for (IndexedBundle bundle : bundles) {
            if (!ids.add(bundle.manifest().id())) {
                throw new IllegalArgumentException(
                        "plugin index contains duplicate bundle id '" + bundle.manifest().id() + "'");
            }
        }
        Set<String> providers = new HashSet<>();
        Map<String, String> providerSources = new HashMap<>();
        for (IndexedBundle bundle : bundles) {
            String source = "bundle:" + bundle.manifest().id();
            for (BundleContribution contribution : bundle.manifest().contributions()) {
                String key = contribution.kind() + "\u0000" + contribution.provider();
                if (!providers.add(key)) {
                    throw new IllegalArgumentException("plugin index contains duplicate provider declaration");
                }
                validateProviderSource(providerSources, contribution.provider(), source);
            }
        }
        for (IndexedLegacyProvider provider : legacyProviders) {
            String key = provider.kind() + "\u0000" + provider.provider();
            if (!providers.add(key)) {
                throw new IllegalArgumentException("plugin index contains duplicate provider declaration");
            }
            String source = "legacy:" + provider.digestMode() + ":" + provider.digest();
            validateProviderSource(providerSources, provider.provider(), source);
        }
    }

    /**
     * Creates an empty schema-current index.
     *
     * @return immutable empty index
     */
    public static PluginIndex empty() {
        return new PluginIndex(CURRENT_SCHEMA_VERSION, List.of(), List.of());
    }

    /** Overflow-safe arithmetic seam used by boundary tests. */
    static void validateProviderLimit(long manifestedProviders, long legacyProviders) {
        if (manifestedProviders < 0 || legacyProviders < 0) {
            throw new IllegalArgumentException("provider counts must not be negative");
        }
        if (manifestedProviders > MAX_PROVIDERS
                || legacyProviders > MAX_PROVIDERS - manifestedProviders) {
            throw providerLimitExceeded();
        }
    }

    private static IllegalArgumentException providerLimitExceeded() {
        return new IllegalArgumentException("plugin index contains too many providers; maximum "
                + MAX_PROVIDERS
                + " covers manifested contributions plus legacy-provider evidence");
    }

    private static void validateProviderSource(
            Map<String, String> providerSources,
            String provider,
            String source
    ) {
        String existing = providerSources.putIfAbsent(provider, source);
        if (existing != null && !existing.equals(source)) {
            throw new IllegalArgumentException(
                    "plugin index maps one provider class to multiple artifact sources");
        }
    }
}
