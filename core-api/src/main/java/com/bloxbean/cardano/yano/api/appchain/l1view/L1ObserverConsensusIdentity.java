package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Canonical consensus identity of one configured L1 observer implementation. */
public record L1ObserverConsensusIdentity(
        int abiVersion,
        String claimSchema,
        int orderingVersion,
        byte[] canonicalIdentityBytes) {

    public L1ObserverConsensusIdentity {
        if (abiVersion < 1 || orderingVersion < 1) {
            throw new IllegalArgumentException("Observer ABI and ordering versions must be positive");
        }
        claimSchema = Objects.requireNonNull(claimSchema, "claimSchema").trim();
        if (claimSchema.isEmpty()) {
            throw new IllegalArgumentException("Observer claim schema must not be empty");
        }
        if (claimSchema.indexOf('\0') >= 0
                || claimSchema.getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException(
                    "Observer claim schema must be at most 256 UTF-8 bytes without NUL");
        }
        canonicalIdentityBytes = Objects.requireNonNull(
                canonicalIdentityBytes, "canonicalIdentityBytes").clone();
        if (canonicalIdentityBytes.length == 0 || canonicalIdentityBytes.length > 64 * 1024) {
            throw new IllegalArgumentException(
                    "Observer canonical identity must contain 1..65536 bytes");
        }
    }

    @Override
    public byte[] canonicalIdentityBytes() {
        return canonicalIdentityBytes.clone();
    }
}
