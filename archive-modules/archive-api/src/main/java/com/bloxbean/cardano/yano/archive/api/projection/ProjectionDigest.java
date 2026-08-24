package com.bloxbean.cardano.yano.archive.api.projection;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Deterministic digest over an ordered chunk list.
 *
 * <p>Chunks are length-prefixed before hashing so that a differently split but
 * byte-identical concatenation produces a different digest. Splitting is part of the
 * projection contract the sink verifies, not an incidental transport detail.
 */
public final class ProjectionDigest {
    private ProjectionDigest() {}

    public static String ofChunks(List<byte[]> chunks) {
        MessageDigest digest = sha256();
        ByteBuffer prefix = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        for (byte[] chunk : chunks) {
            prefix.clear();
            prefix.putInt(chunk.length);
            digest.update(prefix.array());
            digest.update(chunk);
        }
        return hex(digest.digest());
    }

    /** Ordered digest over already-computed section digests, used for a batch receipt. */
    public static String ofDigests(List<String> orderedDigests) {
        MessageDigest digest = sha256();
        for (String value : orderedDigests) {
            digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    public static byte[] concat(List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] value) {
        StringBuilder sb = new StringBuilder(value.length * 2);
        for (byte b : value) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
