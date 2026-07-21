package com.bloxbean.cardano.yano.api.plugin.domain;

import java.util.Objects;

/** Secret-free inventory entry for one validated, bundle-owned domain route. */
public record DomainApiRouteInfo(String bundleId, DomainApiRoute route) {
    public DomainApiRouteInfo {
        Objects.requireNonNull(bundleId, "bundleId");
        if (bundleId.isBlank()) {
            throw new IllegalArgumentException("bundleId must not be blank");
        }
        route = Objects.requireNonNull(route, "route");
    }
}
