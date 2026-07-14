package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.List;

/** Host-dispatched, read-oriented domain API product owned by one bundle. */
public interface DomainApi extends AutoCloseable {
    /** Maximum routes accepted from one selected domain API contribution. */
    int MAX_ROUTES = 64;

    /**
     * Route declarations to snapshot and validate before publication. The host
     * applies {@link DomainApiRouteSet#validateAndOrder(java.util.Collection)}
     * and never publishes this plugin-owned list directly. Plugin tests should
     * apply the same validator before deployment.
     */
    List<DomainApiRoute> routes();

    /** Handles one host-admitted request that matched a declared route. */
    DomainApiResponse handle(DomainApiRequest request) throws Exception;

    @Override
    default void close() {
    }
}
