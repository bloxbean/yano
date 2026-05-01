package com.bloxbean.cardano.yano.wallet.bridge;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record BridgeSession(
        String origin,
        String token,
        Set<BridgePermission> permissions,
        Instant createdAt) {

    public BridgeSession {
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("origin is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }
        Objects.requireNonNull(permissions, "permissions is required");
        permissions = Set.copyOf(permissions);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public boolean allows(BridgePermission permission) {
        return permissions.contains(permission);
    }
}
