package org.yanoproject.runtime.plugins.fixture;

import java.util.List;

/** Test-only SPI of the {@code fixture-product} descriptor kind (no application-ledger semantics). */
public interface FixtureProvider {
    /** @return selector, equal to the manifest contribution name */
    String id();

    /** @return one product, owned by the caller */
    default FixtureProduct create() {
        throw new UnsupportedOperationException("create");
    }

    /** @return several products, owned by the caller */
    default List<FixtureProduct> createAll() {
        return List.of();
    }
}
