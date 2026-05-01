package com.bloxbean.cardano.yano.wallet.bridge;

import java.util.Optional;
import java.util.Set;

public interface BridgeSessionRegistry {
    BridgeSession createSession(String origin, Set<BridgePermission> permissions);

    Optional<BridgeSession> findByToken(String token);

    boolean revoke(String token);
}
