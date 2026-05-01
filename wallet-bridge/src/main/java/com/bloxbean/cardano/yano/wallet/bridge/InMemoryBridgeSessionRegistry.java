package com.bloxbean.cardano.yano.wallet.bridge;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryBridgeSessionRegistry implements BridgeSessionRegistry {
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;
    private final Clock clock;
    private final ConcurrentMap<String, BridgeSession> sessions = new ConcurrentHashMap<>();

    public InMemoryBridgeSessionRegistry() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    InMemoryBridgeSessionRegistry(SecureRandom secureRandom, Clock clock) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public BridgeSession createSession(String origin, Set<BridgePermission> permissions) {
        BridgeSession session;
        do {
            String token = nextToken();
            session = new BridgeSession(origin, token, permissions, Instant.now(clock));
        } while (sessions.putIfAbsent(session.token(), session) != null);
        return session;
    }

    @Override
    public Optional<BridgeSession> findByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(token));
    }

    @Override
    public boolean revoke(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return sessions.remove(token) != null;
    }

    public int size() {
        return sessions.size();
    }

    private String nextToken() {
        byte[] token = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
