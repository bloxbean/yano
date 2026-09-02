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
    /** Greatest block whose artifact set is durably closed to direct staged writes. */
    static final byte[] META_ARTIFACT_SEALED = "artifact-sealed".getBytes(StandardCharsets.UTF_8);
    /** Artifact contracts, kept apart from the section fingerprint they cannot be part of. */
    static final byte[] META_ARTIFACTS = "artifacts".getBytes(StandardCharsets.UTF_8);
    /** Versioned projected-from epoch and origin for every selected artifact. */
    static final byte[] META_ARTIFACT_ENROLLMENTS =
            "artifact-enrollments".getBytes(StandardCharsets.UTF_8);
    private static final String CURSOR_PREFIX = "cursor/";
    private static final byte[] EPOCH_GAP_PREFIX = "epoch-gap/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_STATE_PREFIX = "epoch-state/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_PAUSE_CAUSE_PREFIX =
            "epoch-pause-cause/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_INTERVAL_PREFIX = "epoch-gap-interval/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_RESUME_PREFIX = "epoch-resume/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_PENDING_ARTIFACT_PREFIX =
            "epoch-pending-artifact/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EPOCH_PENDING_GAP_PREFIX =
            "epoch-pending-gap/".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PROJECTION_CAPTURE_FAILURE_PREFIX =
            "projection-capture-failure/".getBytes(StandardCharsets.UTF_8);

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

    static byte[] epochGapPrefix() { return EPOCH_GAP_PREFIX.clone(); }

    static byte[] epochGapKey(String dataset, int epoch) {
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(EPOCH_GAP_PREFIX.length + name.length + 1 + 4)
                .order(ByteOrder.BIG_ENDIAN).put(EPOCH_GAP_PREFIX).put(name).put((byte) '/')
                .putInt(epoch).array();
    }

    static byte[] epochStatePrefix() { return EPOCH_STATE_PREFIX.clone(); }

    static byte[] epochStateKey(String dataset) {
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(EPOCH_STATE_PREFIX.length + name.length)
                .put(EPOCH_STATE_PREFIX).put(name).array();
    }

    static byte[] epochPauseCauseKey(String dataset) {
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(EPOCH_PAUSE_CAUSE_PREFIX.length + name.length)
                .put(EPOCH_PAUSE_CAUSE_PREFIX).put(name).array();
    }

    static byte[] epochIntervalPrefix() { return EPOCH_INTERVAL_PREFIX.clone(); }

    static byte[] epochIntervalKey(String dataset, int causedByEpoch) {
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(EPOCH_INTERVAL_PREFIX.length + name.length + 1 + 4)
                .order(ByteOrder.BIG_ENDIAN).put(EPOCH_INTERVAL_PREFIX).put(name).put((byte) '/')
                .putInt(causedByEpoch).array();
    }

    static byte[] epochResumeKey(String dataset) {
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(EPOCH_RESUME_PREFIX.length + name.length)
                .put(EPOCH_RESUME_PREFIX).put(name).array();
    }

    static byte[] pendingEpochArtifactPrefix() {
        return EPOCH_PENDING_ARTIFACT_PREFIX.clone();
    }

    static byte[] pendingEpochArtifactPrefix(long carrierBlockNumber) {
        return ByteBuffer.allocate(EPOCH_PENDING_ARTIFACT_PREFIX.length + Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN).put(EPOCH_PENDING_ARTIFACT_PREFIX)
                .putLong(carrierBlockNumber).array();
    }

    static byte[] pendingEpochArtifactKey(long carrierBlockNumber, String dataset,
                                          int semanticEpoch, String generation) {
        byte[] prefix = pendingEpochArtifactPrefix(carrierBlockNumber);
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        byte[] gen = generation.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(prefix.length + Integer.BYTES + 1 + name.length + gen.length)
                .order(ByteOrder.BIG_ENDIAN).put(prefix).putInt(semanticEpoch)
                .put(name).put((byte) 0).put(gen).array();
    }

    static long carrierFromPendingEpochArtifactKey(byte[] key) {
        return ByteBuffer.wrap(key, EPOCH_PENDING_ARTIFACT_PREFIX.length, Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN).getLong();
    }

    static byte[] pendingEpochGapPrefix() {
        return EPOCH_PENDING_GAP_PREFIX.clone();
    }

    static byte[] pendingEpochGapPrefix(long carrierBlockNumber) {
        return ByteBuffer.allocate(EPOCH_PENDING_GAP_PREFIX.length + Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN).put(EPOCH_PENDING_GAP_PREFIX)
                .putLong(carrierBlockNumber).array();
    }

    static byte[] pendingEpochGapKey(long carrierBlockNumber, String dataset, int semanticEpoch) {
        byte[] prefix = pendingEpochGapPrefix(carrierBlockNumber);
        byte[] name = dataset.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(prefix.length + Integer.BYTES + name.length)
                .order(ByteOrder.BIG_ENDIAN).put(prefix).putInt(semanticEpoch).put(name).array();
    }

    static long carrierFromPendingEpochGapKey(byte[] key) {
        return ByteBuffer.wrap(key, EPOCH_PENDING_GAP_PREFIX.length, Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN).getLong();
    }

    static byte[] projectionCaptureFailurePrefix() {
        return PROJECTION_CAPTURE_FAILURE_PREFIX.clone();
    }

    static byte[] projectionCaptureFailureKey(long blockNumber) {
        return ByteBuffer.allocate(PROJECTION_CAPTURE_FAILURE_PREFIX.length + Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN).put(PROJECTION_CAPTURE_FAILURE_PREFIX)
                .putLong(blockNumber).array();
    }

    static long blockFromProjectionCaptureFailureKey(byte[] key) {
        return ByteBuffer.wrap(key, PROJECTION_CAPTURE_FAILURE_PREFIX.length, Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN).getLong();
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
