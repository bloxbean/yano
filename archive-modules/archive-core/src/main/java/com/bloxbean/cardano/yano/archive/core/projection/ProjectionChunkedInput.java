package com.bloxbean.cardano.yano.archive.core.projection;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Reads an ordered chunk list as one continuous stream, without concatenating it.
 *
 * <p>{@code ProjectionChunking.join} allocated a second copy of the entire section payload
 * before decoding could start, so a section's bytes were live twice at peak. Chunk boundaries
 * are a transport artefact — a logical fact may straddle two of them — so chunks cannot be
 * decoded independently. Presenting them as a single stream lets a framed decoder read across
 * boundaries while never holding more than the chunk it is currently positioned in.
 *
 * <p>Deliberately not thread-safe: one instance belongs to one decode pass.
 */
final class ProjectionChunkedInput extends InputStream {

    private final List<byte[]> chunks;
    private int chunkIndex;
    private int offset;

    ProjectionChunkedInput(List<byte[]> chunks) {
        this.chunks = Objects.requireNonNull(chunks, "chunks");
    }

    @Override
    public int read() {
        while (chunkIndex < chunks.size()) {
            byte[] chunk = chunks.get(chunkIndex);
            if (offset < chunk.length) return chunk[offset++] & 0xFF;
            chunkIndex++;
            offset = 0;
        }
        return -1;
    }

    @Override
    public int read(byte[] destination, int destinationOffset, int length) {
        Objects.checkFromIndexSize(destinationOffset, length, destination.length);
        if (length == 0) return 0;
        int written = 0;
        while (written < length && chunkIndex < chunks.size()) {
            byte[] chunk = chunks.get(chunkIndex);
            int available = chunk.length - offset;
            if (available <= 0) {
                chunkIndex++;
                offset = 0;
                continue;
            }
            int take = Math.min(available, length - written);
            System.arraycopy(chunk, offset, destination, destinationOffset + written, take);
            offset += take;
            written += take;
        }
        return written == 0 ? -1 : written;
    }

    @Override
    public int available() {
        long remaining = 0;
        for (int i = chunkIndex; i < chunks.size(); i++) {
            remaining += chunks.get(i).length - (i == chunkIndex ? offset : 0);
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }
}
