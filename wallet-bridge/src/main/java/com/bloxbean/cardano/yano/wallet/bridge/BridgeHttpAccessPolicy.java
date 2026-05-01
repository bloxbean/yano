package com.bloxbean.cardano.yano.wallet.bridge;

import java.util.Set;

@FunctionalInterface
public interface BridgeHttpAccessPolicy {
    boolean allow(String origin, Set<BridgePermission> permissions);

    static BridgeHttpAccessPolicy denyAll() {
        return (origin, permissions) -> false;
    }
}
