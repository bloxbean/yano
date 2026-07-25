package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.Objects;

/** Exact app-chain and observed L1 position of a projection transaction. */
public record SourcePoint(
        long appHeight,
        String appBlockHash,
        long l1Slot,
        String l1BlockHash
) implements Comparable<SourcePoint> {
    public static final SourcePoint ORIGIN = new SourcePoint(0, "", 0, "");

    public SourcePoint {
        if (appHeight < 0 || l1Slot < 0) {
            throw new IllegalArgumentException("source heights cannot be negative");
        }
        appBlockHash = hash(appBlockHash, "appBlockHash", appHeight > 0);
        l1BlockHash = hash(l1BlockHash, "l1BlockHash", false);
    }

    @Override
    public int compareTo(SourcePoint other) {
        return Long.compare(appHeight, Objects.requireNonNull(other, "other").appHeight);
    }

    private static String hash(String value, String field, boolean required) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if ((!normalized.isEmpty() && !normalized.matches("[0-9a-f]{64}"))
                || (required && normalized.isEmpty())) {
            throw new IllegalArgumentException(field + " must be lowercase 32-byte hex");
        }
        return normalized;
    }
}
