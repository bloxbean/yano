package com.bloxbean.cardano.yano.appchain.eutxo.client;

import java.util.HexFormat;
import java.util.Objects;

/** Typed value together with the exact committed root that produced it. */
public record EutxoSnapshot<T>(
        String chainId,
        long committedHeight,
        byte[] stateRoot,
        T value
) {
    public EutxoSnapshot {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId is required");
        }
        if (committedHeight < 0) {
            throw new IllegalArgumentException("committedHeight must not be negative");
        }
        Objects.requireNonNull(stateRoot, "stateRoot");
        if (stateRoot.length != 32) {
            throw new IllegalArgumentException("stateRoot must contain 32 bytes");
        }
        stateRoot = stateRoot.clone();
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
    }

    public String stateRootHex() {
        return HexFormat.of().formatHex(stateRoot);
    }
}
