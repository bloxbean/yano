package com.bloxbean.cardano.yano.api.plugin.domain;

/** Host-enforced authorization class for a domain API route. */
public enum DomainApiAccess {
    /** Read-only route available under the app-chain read policy. */
    READ,
    /** Route requiring an unscoped full operator key. */
    PRIVILEGED,
    /**
     * Reserved inventory class. ADR-011.3 v1 does not dispatch these routes
     * through HTTP or the public host/library gateway.
     */
    INTERNAL
}
