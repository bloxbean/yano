package org.yanoproject.catalog;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, validated schema-v1 bundle manifest.
 *
 * @param schemaVersion manifest schema version
 * @param id stable reverse-DNS bundle id
 * @param version semantic bundle version
 * @param yanoApi supported Yano plugin API-major range and minimum additive level
 * @param dependencies required bundle dependencies
 * @param contributions typed provider contributions
 */
public record BundleManifest(
        int schemaVersion,
        String id,
        SemVersion version,
        YanoApiRange yanoApi,
        List<BundleDependency> dependencies,
        List<BundleContribution> contributions
) {
    /** Current supported bundle-manifest schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** Validates, sorts, defensively copies, and creates a manifest. */
    public BundleManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must equal 1");
        }
        id = CatalogValidation.bundleId(id, "id");
        version = Objects.requireNonNull(version, "version must be present");
        yanoApi = Objects.requireNonNull(yanoApi, "yanoApi must be present");
        dependencies = dependencies == null ? List.of() : dependencies.stream()
                .map(dependency -> Objects.requireNonNull(
                        dependency, "dependencies must not contain null entries"))
                .sorted(Comparator.comparing(BundleDependency::id))
                .toList();
        contributions = contributions == null ? List.of() : contributions.stream()
                .map(contribution -> Objects.requireNonNull(
                        contribution, "contributions must not contain null entries"))
                .sorted(Comparator.comparing((BundleContribution value) -> value.kind().manifestKey())
                        .thenComparing(BundleContribution::name)
                        .thenComparing(BundleContribution::provider))
                .toList();
        validateDependencies(id, dependencies);
        validateContributions(id, contributions);
    }

    /**
     * Returns the qualified authoring resource path for this manifest.
     *
     * @return {@code META-INF/yano/plugins/<bundle-id>.json}
     */
    public String resourcePath() {
        return BundleManifestParser.RESOURCE_DIRECTORY + id + BundleManifestParser.RESOURCE_SUFFIX;
    }

    private static void validateDependencies(String bundleId, List<BundleDependency> dependencies) {
        if (dependencies.size() > CatalogValidation.MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("dependencies must contain at most 256 entries");
        }
        Set<String> ids = new HashSet<>();
        for (BundleDependency dependency : dependencies) {
            Objects.requireNonNull(dependency, "dependencies must not contain null entries");
            if (bundleId.equals(dependency.id())) {
                throw new IllegalArgumentException("dependencies must not contain the bundle itself");
            }
            if (!ids.add(dependency.id())) {
                throw new IllegalArgumentException("dependencies must not contain duplicate ids");
            }
        }
    }

    private static void validateContributions(
            String bundleId,
            List<BundleContribution> contributions
    ) {
        if (contributions.size() > CatalogValidation.MAX_CONTRIBUTIONS) {
            throw new IllegalArgumentException("contributions must contain at most 256 entries");
        }
        Set<ContributionKey> keys = new HashSet<>();
        Set<ProviderKey> providers = new HashSet<>();
        Map<ContributionKind, Integer> counts = new HashMap<>();
        for (BundleContribution contribution : contributions) {
            Objects.requireNonNull(contribution, "contributions must not contain null entries");
            ContributionKind kind = contribution.kind();
            if (counts.merge(kind, 1, Integer::sum) > kind.maxPerBundle()) {
                throw new IllegalArgumentException("contributions must declare at most "
                        + kind.maxPerBundle() + " " + kind.manifestKey() + " contributions");
            }
            if (kind.nameIsBundleId() && !bundleId.equals(contribution.name())) {
                throw new IllegalArgumentException(kind.manifestKey()
                        + " contribution name must equal the containing bundle id");
            }
            if (!keys.add(new ContributionKey(contribution.kind(), contribution.name()))) {
                throw new IllegalArgumentException("contributions must not contain duplicate kind/name pairs");
            }
            if (!providers.add(new ProviderKey(contribution.kind(), contribution.provider()))) {
                throw new IllegalArgumentException("contributions must not contain duplicate kind/provider pairs");
            }
        }
    }

    private record ContributionKey(ContributionKind kind, String name) {
    }

    private record ProviderKey(ContributionKind kind, String provider) {
    }
}
