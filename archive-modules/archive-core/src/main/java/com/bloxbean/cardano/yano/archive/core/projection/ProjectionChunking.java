package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Deterministic fixed-size splitting of an encoded section.
 *
 * <p>ADR-039 §2 requires that no physical value grow without a configured bound. Splitting
 * is deterministic — the same payload and chunk size always produce the same chunks — so
 * the digest over the chunk list is reproducible on replay.
 */
public final class ProjectionChunking {
    /** Default physical value bound. Keeps individual RocksDB values well clear of pathological sizes. */
    public static final int DEFAULT_CHUNK_BYTES = 1 << 20;

    private ProjectionChunking() {}

    public static List<byte[]> split(byte[] payload, int chunkBytes) {
        if (chunkBytes < 1) throw new IllegalArgumentException("chunkBytes must be positive");
        if (payload.length == 0) return List.of();
        List<byte[]> chunks = new ArrayList<>((payload.length + chunkBytes - 1) / chunkBytes);
        for (int offset = 0; offset < payload.length; offset += chunkBytes) {
            chunks.add(Arrays.copyOfRange(payload, offset, Math.min(offset + chunkBytes, payload.length)));
        }
        return List.copyOf(chunks);
    }

    public static byte[] join(List<byte[]> chunks) {
        int total = 0;
        for (byte[] chunk : chunks) total += chunk.length;
        byte[] joined = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, offset, chunk.length);
            offset += chunk.length;
        }
        return joined;
    }
}
