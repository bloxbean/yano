package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.Objects;

/** One canonical key/value entry in a snapshot chunk. */
public record SnapshotEntry(byte[] key, byte[] value) {
    public SnapshotEntry {
        key = Objects.requireNonNull(key, "key").clone();
        value = Objects.requireNonNull(value, "value").clone();
        if (key.length == 0) throw new IllegalArgumentException("snapshot key must not be empty");
        if (startsWithReservedPrefix(key)) {
            throw new IllegalArgumentException("snapshot key must not use reserved ~snapshot/ prefix");
        }
    }

    @Override public byte[] key() { return key.clone(); }
    @Override public byte[] value() { return value.clone(); }

    private static boolean startsWithReservedPrefix(byte[] key) {
        byte[] prefix = "~snapshot/".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (key[i] != prefix[i]) return false;
        return true;
    }
}
