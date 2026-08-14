package com.bloxbean.cardano.yano.catalog;

import java.util.Objects;

/**
 * One manifest declaration correlated with a typed ServiceLoader provider.
 *
 * @param kind supported provider kind
 * @param name public selector advertised by the provider
 * @param provider fully qualified ASCII binary provider class name
 */
public record BundleContribution(
        ContributionKind kind,
        String name,
        String provider
) {
    /** Validates and creates a contribution declaration. */
    public BundleContribution {
        kind = Objects.requireNonNull(kind, "contributions[].kind must be present");
        name = CatalogValidation.contributionName(kind, name, "contributions[].name");
        provider = CatalogValidation.providerClass(provider, "contributions[].provider");
    }
}
