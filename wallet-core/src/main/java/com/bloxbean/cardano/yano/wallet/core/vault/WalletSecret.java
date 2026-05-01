package com.bloxbean.cardano.yano.wallet.core.vault;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

public record WalletSecret(
        SecretKind kind,
        byte[] secretBytes,
        String network,
        int accountIndex,
        Instant createdAt) {

    public WalletSecret {
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(secretBytes, "secretBytes is required");
        if (secretBytes.length == 0) {
            throw new IllegalArgumentException("secretBytes must not be empty");
        }
        if (network == null || network.isBlank()) {
            throw new IllegalArgumentException("network is required");
        }
        if (accountIndex < 0) {
            throw new IllegalArgumentException("accountIndex must not be negative");
        }
        secretBytes = secretBytes.clone();
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    @Override
    public byte[] secretBytes() {
        return secretBytes.clone();
    }

    public void destroy() {
        Arrays.fill(secretBytes, (byte) 0);
    }
}
