package com.bloxbean.cardano.yano.api.plugin.domain;

/** ServiceLoader factory for one manifested, bundle-owned domain API. */
public interface DomainApiProvider {
    /**
     * Stable reverse-DNS identity. For schema v1 the runtime requires this to
     * equal both the manifest contribution name and containing bundle id.
     */
    String id();

    /** Creates one lifecycle-owned product using only constrained host services. */
    DomainApi create(DomainApiContext context);
}
