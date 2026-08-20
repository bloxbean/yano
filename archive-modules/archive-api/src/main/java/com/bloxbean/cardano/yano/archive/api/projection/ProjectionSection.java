package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** One section's ordered chunk payloads together with the counts its manifest claims. */
public record ProjectionSection(ProjectionSectionType type, int version, List<byte[]> chunks, long rowCount) {
    public ProjectionSection {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(chunks, "chunks");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        if (rowCount < 0) throw new IllegalArgumentException("rowCount must not be negative");
        List<byte[]> copies = new ArrayList<>(chunks.size());
        for (byte[] chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
            copies.add(Arrays.copyOf(chunk, chunk.length));
        }
        chunks = List.copyOf(copies);
    }

    @Override
    public List<byte[]> chunks() {
        List<byte[]> copies = new ArrayList<>(chunks.size());
        for (byte[] chunk : chunks) copies.add(Arrays.copyOf(chunk, chunk.length));
        return List.copyOf(copies);
    }

    public long byteCount() {
        long total = 0;
        for (byte[] chunk : chunks) total += chunk.length;
        return total;
    }

    public ProjectionSectionManifest manifest() {
        return new ProjectionSectionManifest(type, version, chunks.size(), rowCount, byteCount(),
                ProjectionDigest.ofChunks(chunks));
    }
}
