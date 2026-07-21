package com.bloxbean.cardano.yano.appchain.roles.internal;

import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class RoleState {
    private static final byte[] PENDING_GOVERNANCE_COUNT =
            "~governance/pending-count".getBytes(StandardCharsets.US_ASCII);

    private RoleState() {
    }

    public static long pointer(AppStateReader state, byte[] key) {
        byte[] value = state.get(key).orElse(null);
        if (value == null) return 0;
        if (value.length != Long.BYTES) throw new IllegalStateException("corrupt role-workflow pointer");
        long revision = ByteBuffer.wrap(value).getLong();
        if (revision < 1) throw new IllegalStateException("corrupt role-workflow pointer");
        return revision;
    }

    public static void pointer(AppStateWriter state, byte[] key, long revision) {
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        state.put(key, ByteBuffer.allocate(Long.BYTES).putLong(revision).array());
    }

    public static byte[] pointerBytes(AppStateReader state, byte[] key) {
        byte[] value = state.get(key).orElse(null);
        if (value == null) return new byte[0];
        pointer(state, key);
        return value.clone();
    }

    static int pendingCount(AppStateReader state) {
        byte[] value = state.get(PENDING_GOVERNANCE_COUNT).orElse(null);
        if (value == null) return 0;
        if (value.length != Integer.BYTES) throw new IllegalStateException("corrupt governance count");
        int count = ByteBuffer.wrap(value).getInt();
        if (count < 0) throw new IllegalStateException("corrupt governance count");
        return count;
    }

    static void pendingCount(AppStateWriter state, int count) {
        if (count < 0) throw new IllegalStateException("negative governance count");
        state.put(PENDING_GOVERNANCE_COUNT, ByteBuffer.allocate(Integer.BYTES).putInt(count).array());
    }
}
