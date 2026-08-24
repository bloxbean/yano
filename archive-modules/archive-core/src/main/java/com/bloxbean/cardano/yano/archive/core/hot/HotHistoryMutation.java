package com.bloxbean.cardano.yano.archive.core.hot;

import java.util.Arrays;

public record HotHistoryMutation(byte[] key, byte[] value) {
    public HotHistoryMutation {
        if (key == null || key.length == 0) throw new IllegalArgumentException("hot-history key is required");
        key = Arrays.copyOf(key, key.length);
        value = value == null ? null : Arrays.copyOf(value, value.length);
    }

    @Override public byte[] key() { return Arrays.copyOf(key, key.length); }
    @Override public byte[] value() { return value == null ? null : Arrays.copyOf(value, value.length); }
}
