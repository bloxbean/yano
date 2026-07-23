package com.bloxbean.cardano.yano.catalog;

/**
 * A required bundle dependency with optional inclusive/exclusive version bounds.
 *
 * @param id required bundle id
 * @param minVersion optional inclusive lower bound
 * @param maxVersionExclusive optional exclusive upper bound
 */
public record BundleDependency(
        String id,
        SemVersion minVersion,
        SemVersion maxVersionExclusive
) {
    /** Validates and creates a dependency declaration. */
    public BundleDependency {
        id = CatalogValidation.bundleId(id, "dependencies[].id");
        if (minVersion != null && maxVersionExclusive != null
                && minVersion.compareTo(maxVersionExclusive) >= 0) {
            throw new IllegalArgumentException(
                    "dependency version range must satisfy minVersion < maxVersionExclusive");
        }
    }

    /**
     * Tests whether a discovered dependency version satisfies this range.
     *
     * @param version version to test
     * @return {@code true} when the version is non-null and within the bounds
     */
    public boolean accepts(SemVersion version) {
        if (version == null) {
            return false;
        }
        return (minVersion == null || version.compareTo(minVersion) >= 0)
                && (maxVersionExclusive == null || version.compareTo(maxVersionExclusive) < 0);
    }
}
