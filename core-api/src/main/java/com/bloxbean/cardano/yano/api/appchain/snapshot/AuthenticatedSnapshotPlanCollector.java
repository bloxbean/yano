package com.bloxbean.cardano.yano.api.appchain.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded in-memory intent collector; it never performs storage or I/O. */
public final class AuthenticatedSnapshotPlanCollector {
    private static final AuthenticatedSnapshotPlanCollector DISABLED =
            new AuthenticatedSnapshotPlanCollector(false, 0, 0);

    private final boolean enabled;
    private final int maxOperations;
    private final long maxBytes;
    private final List<Intent> intents = new ArrayList<>();
    private long bytes;
    private long operations;

    public AuthenticatedSnapshotPlanCollector(int maxOperations, long maxBytes) {
        this(true, maxOperations, maxBytes);
    }

    private AuthenticatedSnapshotPlanCollector(boolean enabled, int maxOperations, long maxBytes) {
        if (enabled && (maxOperations <= 0 || maxBytes <= 0)) {
            throw new IllegalArgumentException("collector limits must be positive");
        }
        this.enabled = enabled;
        this.maxOperations = maxOperations;
        this.maxBytes = maxBytes;
    }

    public static AuthenticatedSnapshotPlanCollector disabled() { return DISABLED; }

    SnapshotBuildTokenV1 begin(AuthenticatedSnapshotSeriesDescriptorV1 descriptor, long sequence, String snapshotId,
               SnapshotSourceBoundary boundary, long baseHeight,
               long fromHeight, long throughHeight, byte[] sourceRoot,
               long expectedChunks, long expectedEntries) {
        requireEnabled();
        if (sequence < 0 || baseHeight < 0 || fromHeight < 0 || throughHeight < fromHeight
                || expectedChunks < 0 || expectedEntries < 0
                || expectedEntries > descriptor.maxEntriesPerSnapshot()) {
            throw new IllegalArgumentException("invalid snapshot begin bounds");
        }
        byte[] root = require32(sourceRoot, "sourceDatasetRoot");
        SnapshotDescriptorDraftV1 draft = new SnapshotDescriptorDraftV1(descriptor, sequence,
                snapshotId, boundary, baseHeight, fromHeight, throughHeight, root,
                expectedChunks, expectedEntries);
        SnapshotBuildTokenV1 token = draft.token();
        add(new Begin(descriptor, sequence, Objects.requireNonNull(snapshotId, "snapshotId"),
                Objects.requireNonNull(boundary, "boundary"), baseHeight, fromHeight,
                throughHeight, root, expectedChunks, expectedEntries,
                token.descriptorDraftDigest()),
                root.length + snapshotId.length(), 1);
        return token;
    }

    void appendChunk(AuthenticatedSnapshotSeriesDescriptorV1 descriptor, SnapshotBuildTokenV1 token,
                     long chunkIndex, List<SnapshotEntry> entries) {
        requireEnabled();
        Objects.requireNonNull(token, "token");
        long sequence = token.sequence();
        if (sequence < 0 || chunkIndex < 0) throw new IllegalArgumentException("negative sequence/chunk");
        List<SnapshotEntry> copy = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (copy.size() > descriptor.maxEntriesPerChunk()) {
            throw new IllegalArgumentException("snapshot chunk exceeds entry limit");
        }
        long chunkBytes = 0;
        byte[] prior = null;
        for (SnapshotEntry entry : copy) {
            if (entry.key().length > descriptor.maxKeyBytes()
                    || entry.value().length > descriptor.maxValueBytes()) {
                throw new IllegalArgumentException("snapshot entry exceeds key/value limits");
            }
            chunkBytes = Math.addExact(chunkBytes, entry.key().length + entry.value().length);
            if (prior != null && compareUnsigned(prior, entry.key()) >= 0) {
                throw new IllegalArgumentException("snapshot keys must be strictly increasing");
            }
            prior = entry.key();
        }
        if (chunkBytes > descriptor.maxChunkBytes()) {
            throw new IllegalArgumentException("snapshot chunk exceeds byte limit");
        }
        add(new AppendChunk(descriptor, sequence, token.descriptorDraftDigest(), chunkIndex, copy), chunkBytes,
                Math.max(1, copy.size()));
    }

    void seal(AuthenticatedSnapshotSeriesDescriptorV1 descriptor, SnapshotBuildTokenV1 token) {
        requireEnabled();
        Objects.requireNonNull(token, "token");
        long sequence = token.sequence();
        if (sequence < 0) throw new IllegalArgumentException("negative sequence");
        add(new Seal(descriptor, sequence, token.descriptorDraftDigest()), 0, 1);
    }

    public List<Intent> intents() { return List.copyOf(intents); }
    public boolean isEmpty() { return intents.isEmpty(); }
    public long operationCount() { return operations; }
    public long byteCount() { return bytes; }

    private void add(Intent intent, long additionalBytes, long additionalOperations) {
        if (additionalOperations <= 0 || operations > maxOperations - additionalOperations
                || bytes > maxBytes - additionalBytes) {
            throw new IllegalArgumentException("snapshot plan exceeds block collector limits");
        }
        intents.add(intent);
        bytes += additionalBytes;
        operations += additionalOperations;
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("authenticated snapshots are disabled");
    }

    private static byte[] require32(byte[] value, String name) {
        byte[] copy = Objects.requireNonNull(value, name).clone();
        if (copy.length != 32) throw new IllegalArgumentException(name + " must be 32 bytes");
        return copy;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    public sealed interface Intent permits Begin, AppendChunk, Seal {
        AuthenticatedSnapshotSeriesDescriptorV1 descriptor();
        long sequence();
    }

    public record Begin(AuthenticatedSnapshotSeriesDescriptorV1 descriptor, long sequence,
                        String snapshotId, SnapshotSourceBoundary boundary, long baseHeight,
                        long coveredFromHeight, long coveredThroughHeight, byte[] sourceDatasetRoot,
                        long expectedChunks, long expectedEntries,
                        byte[] descriptorDraftDigest) implements Intent {
        public Begin {
            sourceDatasetRoot = sourceDatasetRoot.clone();
            descriptorDraftDigest = descriptorDraftDigest.clone();
        }
        @Override public byte[] sourceDatasetRoot() { return sourceDatasetRoot.clone(); }
        @Override public byte[] descriptorDraftDigest() { return descriptorDraftDigest.clone(); }
    }

    public record AppendChunk(AuthenticatedSnapshotSeriesDescriptorV1 descriptor, long sequence,
                              byte[] descriptorDraftDigest, long chunkIndex,
                              List<SnapshotEntry> entries) implements Intent {
        public AppendChunk {
            descriptorDraftDigest = descriptorDraftDigest.clone();
            entries = List.copyOf(entries);
        }
        @Override public byte[] descriptorDraftDigest() { return descriptorDraftDigest.clone(); }
    }

    public record Seal(AuthenticatedSnapshotSeriesDescriptorV1 descriptor,
                       long sequence, byte[] descriptorDraftDigest) implements Intent {
        public Seal { descriptorDraftDigest = descriptorDraftDigest.clone(); }
        @Override public byte[] descriptorDraftDigest() { return descriptorDraftDigest.clone(); }
    }
}
