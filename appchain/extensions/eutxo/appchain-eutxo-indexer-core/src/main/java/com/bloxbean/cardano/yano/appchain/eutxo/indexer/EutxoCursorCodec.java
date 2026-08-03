package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Opaque cursor tied to one chain and ordering. */
public final class EutxoCursorCodec {
    private static final String PREFIX = "c1_";

    private EutxoCursorCodec() {
    }

    public static String encode(String chainId, String ordering, long before) {
        if (before == 0) {
            return "";
        }
        if (before < 0) {
            throw new IllegalArgumentException("cursor position cannot be negative");
        }
        ByteBuffer value = ByteBuffer.allocate(16);
        value.putLong(before);
        value.put(tag(chainId, ordering, before));
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.array());
    }

    public static long decode(
            String chainId,
            String ordering,
            String cursor
    ) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        if (!cursor.startsWith(PREFIX) || cursor.length() > 64) {
            throw new IllegalArgumentException("invalid cursor");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder()
                    .decode(cursor.substring(PREFIX.length()));
            if (decoded.length != 16) {
                throw new IllegalArgumentException("invalid cursor");
            }
            ByteBuffer value = ByteBuffer.wrap(decoded);
            long before = value.getLong();
            byte[] supplied = new byte[8];
            value.get(supplied);
            if (before < 1 || !MessageDigest.isEqual(
                    supplied, tag(chainId, ordering, before))) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return before;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private static byte[] tag(
            String chainId,
            String ordering,
            long before
    ) {
        String domain = Objects.requireNonNull(chainId, "chainId") + "\n"
                + Objects.requireNonNull(ordering, "ordering") + "\n"
                + before;
        try {
            return Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(
                            domain.getBytes(StandardCharsets.UTF_8)),
                    8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
