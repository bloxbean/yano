package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Key encoding for the projection outbox column families.
 *
 * <p>Block numbers are big-endian so RocksDB's byte order is canonical block order and
 * an ordered scan is a sequential read. Section records sort as
 * {@code block, section, manifest-before-chunks}, so assembling one envelope is a
 * single forward scan rather than a series of point lookups.
 */
final class ProjectionOutboxKeys {
    static final byte KIND_MANIFEST = 0;
    static final byte KIND_CHUNK = 1;

    static final byte[] META_IDENTITY = "identity".getBytes(StandardCharsets.UTF_8);
    static final byte[] META_ACK = "ack".getBytes(StandardCharsets.UTF_8);
    /** Artifact contracts, kept apart from the section fingerprint they cannot be part of. */
    static final byte[] META_ARTIFACTS = "artifacts".getBytes(StandardCharsets.UTF_8);
    private static final String CURSOR_PREFIX = "cursor/";

    /** Cursor for the contributor that writes canonical block identity. */
    static final byte[] META_CURSOR_IDENTITY = (CURSOR_PREFIX + "identity").getBytes(StandardCharsets.UTF_8);

    private ProjectionOutboxKeys() {}

    static byte[] blockKey(long blockNumber) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockNumber).array();
    }

    static long blockFromKey(byte[] key) {
        return ByteBuffer.wrap(key, 0, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    static byte[] sectionManifestKey(long blockNumber, ProjectionSectionType type) {
        return sectionKey(blockNumber, type, KIND_MANIFEST, 0);
    }

    static byte[] sectionChunkKey(long blockNumber, ProjectionSectionType type, int chunkIndex) {
        return sectionKey(blockNumber, type, KIND_CHUNK, chunkIndex);
    }

    static byte[] sectionKey(long blockNumber, ProjectionSectionType type, byte kind, int chunkIndex) {
        return ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
                .putLong(blockNumber).put((byte) type.code()).put(kind).putInt(chunkIndex).array();
    }

    static byte[] cursorKey(ProjectionSectionType type) {
        return (CURSOR_PREFIX + type.wireName()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Key one artifact reference.
     *
     * <p>The source generation is part of the key, not decoration. A dataset can stage several
     * artifacts for the SAME epoch at the same boundary - rewards alone produce separate parts
     * for the calculator, MIR certificates and governance withdrawals - and keying only by
     * (block, dataset, epoch) silently overwrote every part but the last. That is invisible loss:
     * the surviving reference looks like a complete epoch.
     */
    static byte[] artifactKey(long blockNumber, String dataset, int semanticEpoch, String generation) {
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        byte[] gen = generation.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(12 + 1 + name.length + gen.length).order(ByteOrder.BIG_ENDIAN)
                .putLong(blockNumber).putInt(semanticEpoch).put(name).put((byte) 0).put(gen).array();
    }

    static byte[] encodeLong(long value) {
        return blockKey(value);
    }

    static long decodeLong(byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).getLong();
    }
}
