package com.bloxbean.cardano.yano.api.appchain;

import java.util.List;
import java.util.Objects;

/**
 * Threshold signature certificate over an app block hash. A block is APP_FINAL
 * once its cert carries signatures from at least the configured threshold of
 * distinct group members. Every signature is verified against the membership
 * registry — no trust-by-mode shortcuts (ADR app-layer/005 D2).
 *
 * @param scheme     0 = Ed25519 threshold signatures over block-hash
 * @param signatures member signatures, deduplicated by signer
 */
public record FinalityCert(int scheme, List<Signature> signatures) {

    public static final int SCHEME_ED25519 = 0;

    public FinalityCert {
        signatures = signatures != null ? List.copyOf(signatures) : List.of();
    }

    public static FinalityCert empty() {
        return new FinalityCert(SCHEME_ED25519, List.of());
    }

    public record Signature(byte[] signer, byte[] signature) {
        public Signature {
            Objects.requireNonNull(signer, "signer");
            Objects.requireNonNull(signature, "signature");
        }
    }
}
