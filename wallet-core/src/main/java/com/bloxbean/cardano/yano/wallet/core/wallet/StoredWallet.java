package com.bloxbean.cardano.yano.wallet.core.wallet;

import java.time.Instant;

public record StoredWallet(
        String id,
        String seedId,
        String name,
        String networkId,
        int accountIndex,
        String baseAddress,
        String stakeAddress,
        String drepId,
        String vaultFile,
        Instant createdAt,
        Instant updatedAt) {

    public StoredWallet {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (seedId == null || seedId.isBlank()) {
            seedId = id;
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId is required");
        }
        if (accountIndex < 0) {
            throw new IllegalArgumentException("accountIndex must not be negative");
        }
        if (baseAddress == null || baseAddress.isBlank()) {
            throw new IllegalArgumentException("baseAddress is required");
        }
        if (vaultFile == null || vaultFile.isBlank()) {
            throw new IllegalArgumentException("vaultFile is required");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
