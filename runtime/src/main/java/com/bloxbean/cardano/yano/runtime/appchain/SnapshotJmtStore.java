package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.core.NibblePath;
import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtEncoding;
import com.bloxbean.cardano.vds.jmt.JmtNode;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.NodeKey;
import com.bloxbean.cardano.vds.jmt.store.JmtAccessCoordinator;
import com.bloxbean.cardano.vds.jmt.store.JmtAccessLease;
import com.bloxbean.cardano.vds.jmt.store.JmtFormatDescriptor;
import com.bloxbean.cardano.vds.jmt.store.JmtFormatMismatchException;
import com.bloxbean.cardano.vds.jmt.store.JmtStore;
import com.bloxbean.cardano.vds.jmt.store.JmtStoreInspection;
import org.rocksdb.WriteBatch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Prefix-isolated classic JMT stored in the authenticated-snapshot column family.
 * Calculations update an overlay and are staged into the app block WriteBatch,
 * preserving atomicity with descriptor publication without rebuilding old entries.
 */
final class SnapshotJmtStore implements JmtStore {
    private static final byte NODE = 0x4e;
    private static final byte VALUE = 0x56;
    private static final byte ROOT = 0x52;
    private static final byte LATEST_ROOT = 0x4c;
    private static final byte LATEST_VERSION = 0x76;
    private static final int HASH_BYTES = 32;
    private static final long MAX_INSPECTION_MATERIAL_BYTES = 512L * 1024 * 1024;

    private final AppLedgerStore ledger;
    private final byte[] storagePrefix;
    private final long maximumMutations;
    private final long maximumBytes;
    private final long inspectionMaximumBytes;
    private final NavigableMap<BytesKey, byte[]> overlay = new TreeMap<>();
    private final JmtAccessCoordinator coordinator = new JmtAccessCoordinator();
    private final ThreadLocal<Capture> capture = new ThreadLocal<>();
    private long mutationBytes;

    SnapshotJmtStore(AppLedgerStore ledger, byte[] storagePrefix,
                     long maximumMutations, long maximumBytes,
                     long inspectionMaximumBytes) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.storagePrefix = Objects.requireNonNull(storagePrefix, "storagePrefix").clone();
        this.maximumMutations = maximumMutations;
        this.maximumBytes = maximumBytes;
        this.inspectionMaximumBytes = Math.min(
                inspectionMaximumBytes, MAX_INSPECTION_MATERIAL_BYTES);
    }

    byte[] putNext(JellyfishMerkleTree tree, Map<byte[], byte[]> updates) {
        long version = latestRoot().map(root -> Math.addExact(root.version(), 1)).orElse(0L);
        Capture current = new Capture(version);
        if (capture.get() != null) throw new IllegalStateException("nested snapshot JMT update");
        capture.set(current);
        try {
            JellyfishMerkleTree.CommitResult result = tree.put(version, updates);
            Prepared prepared = Objects.requireNonNull(current.prepared,
                    "JMT did not produce a prepared update");
            if (result.version() != version || !Arrays.equals(result.rootHash(), prepared.rootHash)) {
                throw new IllegalStateException("snapshot JMT result differs from captured update");
            }
            accept(prepared);
            return prepared.rootHash.clone();
        } finally {
            capture.remove();
        }
    }

    void stage(WriteBatch batch) {
        overlay.forEach((key, value) -> {
            byte[] full = full(key.bytes);
            if (value == null) ledger.stageDeleteSnapshotNode(batch, full);
            else ledger.stageSnapshotNode(batch, full, value);
        });
    }

    long latestVersion() {
        return latestRoot().orElseThrow(() -> new IllegalStateException(
                "snapshot JMT has no committed root")).version();
    }

    long mutationCount() { return overlay.size(); }
    long mutationBytes() { return mutationBytes; }

    @Override public JmtAccessCoordinator accessCoordinator() { return coordinator; }

    @Override public void ensureFormat(JmtFormatDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor").requirePersistent();
        if (!JmtProfile.classicBlake2b256V1().format().equals(descriptor)) {
            throw new JmtFormatMismatchException("Snapshot JMT requires classic Blake2b-256 v1");
        }
    }

    @Override public Optional<JmtFormatDescriptor> formatDescriptor() {
        return Optional.of(JmtProfile.classicBlake2b256V1().format());
    }

    @Override public JmtStoreInspection inspect(int maxRecords) {
        if (maxRecords <= 0) throw new IllegalArgumentException("maxRecords must be positive");
        List<AppLedgerStore.SnapshotNodeRecord> records = ledger.snapshotNodes(storagePrefix,
                Math.addExact((long) maxRecords, 1), inspectionMaximumBytes);
        boolean truncated = records.size() > maxRecords;
        List<VersionedRoot> roots = new ArrayList<>();
        List<JmtStoreInspection.NodeRecord> nodes = new ArrayList<>();
        List<JmtStoreInspection.ValueRecord> values = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        int through = Math.min(records.size(), maxRecords);
        for (int index = 0; index < through; index++) {
            byte[] key = records.get(index).key();
            byte[] value = records.get(index).value();
            try {
                if (key.length == 1 && (key[0] == LATEST_ROOT || key[0] == LATEST_VERSION)) {
                    continue;
                }
                if (key.length == 1 + Long.BYTES && key[0] == ROOT) {
                    roots.add(new VersionedRoot(ByteBuffer.wrap(key, 1, Long.BYTES).getLong(), value));
                } else if (key.length > 1 && key[0] == NODE) {
                    nodes.add(new JmtStoreInspection.NodeRecord(
                            NodeKey.fromBytes(Arrays.copyOfRange(key, 1, key.length)),
                            JmtEncoding.decode(value)));
                } else if (key.length == 1 + HASH_BYTES + Long.BYTES && key[0] == VALUE) {
                    values.add(new JmtStoreInspection.ValueRecord(
                            Arrays.copyOfRange(key, 1, 1 + HASH_BYTES),
                            ByteBuffer.wrap(key, 1 + HASH_BYTES, Long.BYTES).getLong(),
                            value, false));
                } else {
                    issues.add("Malformed or unknown snapshot JMT record");
                }
            } catch (RuntimeException malformed) {
                issues.add("Malformed snapshot JMT record: " + malformed.getMessage());
            }
        }
        return new JmtStoreInspection(roots, latestRoot().orElse(null), nodes, values,
                List.of(), issues, truncated);
    }

    @Override public Optional<VersionedRoot> latestRoot() {
        byte[] root = get(new byte[]{LATEST_ROOT});
        byte[] version = get(new byte[]{LATEST_VERSION});
        if (root == null && version == null) return Optional.empty();
        if (root == null || root.length != HASH_BYTES || version == null
                || version.length != Long.BYTES) {
            throw new JmtFormatMismatchException("Malformed snapshot JMT latest root");
        }
        long decoded = ByteBuffer.wrap(version).getLong();
        requireVersion(decoded);
        return Optional.of(new VersionedRoot(decoded, root));
    }

    @Override public Optional<byte[]> rootHash(long version) {
        requireVersion(version);
        return latestRoot().filter(root -> root.version() == version)
                .map(VersionedRoot::rootHash);
    }

    @Override public Optional<NodeEntry> getNode(long version, NibblePath path) {
        requireVersion(version);
        Objects.requireNonNull(path, "path");
        byte[] encoded = NodeKey.of(path, version).toBytes();
        Floor floor = floor(NODE, encoded);
        if (floor == null) return Optional.empty();
        NodeKey candidate = NodeKey.fromBytes(floor.key);
        return comparePath(candidate.path(), path) == 0 && candidate.version() <= version
                ? Optional.of(new NodeEntry(candidate, JmtEncoding.decode(floor.value)))
                : Optional.empty();
    }

    @Override public Optional<JmtNode> getNode(NodeKey nodeKey) {
        byte[] encoded = get(relative(NODE, Objects.requireNonNull(nodeKey, "nodeKey").toBytes()));
        return encoded == null ? Optional.empty() : Optional.of(JmtEncoding.decode(encoded));
    }

    @Override public Optional<byte[]> getValue(byte[] keyHash) {
        return latestRoot().flatMap(root -> getValueAt(keyHash, root.version()));
    }

    @Override public Optional<byte[]> getValueAt(byte[] keyHash, long version) {
        requireHash(keyHash);
        requireVersion(version);
        Floor floor = floor(VALUE, ByteBuffer.allocate(HASH_BYTES + Long.BYTES)
                .put(keyHash).putLong(version).array());
        if (floor == null || floor.key.length != HASH_BYTES + Long.BYTES
                || !Arrays.equals(keyHash, Arrays.copyOf(floor.key, HASH_BYTES))) {
            return Optional.empty();
        }
        return Optional.of(floor.value.clone());
    }

    @Override public CommitBatch beginCommit(long version, CommitConfig config) {
        requireVersion(version);
        Capture current = capture.get();
        if (current == null || current.version != version || current.prepared != null) {
            throw new IllegalStateException("snapshot JMT commits require a captured calculation");
        }
        JmtAccessLease lease = coordinator.tryAcquireUpdate("snapshot-prepare", version);
        return new CapturingBatch(current, Objects.requireNonNull(config, "config"), lease);
    }

    @Override public List<NodeKey> staleNodesUpTo(long versionInclusive) { return List.of(); }
    @Override public int pruneUpTo(long versionInclusive) { return 0; }
    @Override public void close() { }

    private void accept(Prepared prepared) {
        Optional<VersionedRoot> actual = latestRoot();
        if (!sameRoot(prepared.expectedLatest, actual)) {
            throw new IllegalStateException("snapshot JMT prepared update is stale");
        }
        for (NodeKey stale : prepared.staleNodes) remove(relative(NODE, stale.toBytes()));
        prepared.nodes.forEach((key, node) -> put(relative(NODE, key.toBytes()), node.encode()));
        prepared.values.forEach(value -> put(relative(VALUE, ByteBuffer.allocate(HASH_BYTES + Long.BYTES)
                .put(value.keyHash).putLong(prepared.version).array()), value.value));
        actual.ifPresent(previous -> remove(relative(ROOT,
                ByteBuffer.allocate(Long.BYTES).putLong(previous.version()).array())));
        put(relative(ROOT, ByteBuffer.allocate(Long.BYTES).putLong(prepared.version).array()),
                prepared.rootHash);
        put(new byte[]{LATEST_ROOT}, prepared.rootHash);
        put(new byte[]{LATEST_VERSION}, ByteBuffer.allocate(Long.BYTES)
                .putLong(prepared.version).array());
        if (overlay.size() > maximumMutations || mutationBytes > maximumBytes) {
            throw new IllegalArgumentException("snapshot JMT generated-node budget exceeded");
        }
    }

    private void put(byte[] key, byte[] value) {
        BytesKey wrapped = new BytesKey(key);
        byte[] previous = overlay.put(wrapped, value.clone());
        mutationBytes = Math.addExact(mutationBytes, key.length + value.length
                - (previous != null ? key.length + previous.length : 0));
    }

    private void remove(byte[] key) {
        BytesKey wrapped = new BytesKey(key);
        byte[] previous = overlay.put(wrapped, null);
        mutationBytes = Math.addExact(mutationBytes, key.length
                - (previous != null ? key.length + previous.length : 0));
    }

    private byte[] get(byte[] relative) {
        BytesKey key = new BytesKey(relative);
        if (overlay.containsKey(key)) {
            byte[] value = overlay.get(key);
            return value != null ? value.clone() : null;
        }
        return ledger.snapshotNode(full(relative));
    }

    private Floor floor(byte type, byte[] seek) {
        byte[] relativeSeek = relative(type, seek);
        Map.Entry<BytesKey, byte[]> pending = overlay.floorEntry(new BytesKey(relativeSeek));
        while (pending != null && (pending.getKey().bytes[0] != type || pending.getValue() == null)) {
            if (pending.getKey().bytes[0] != type) { pending = null; break; }
            pending = overlay.lowerEntry(pending.getKey());
        }
        byte[] subspace = full(new byte[]{type});
        AppLedgerStore.SnapshotNodeRecord durable = ledger.snapshotFloor(
                subspace, full(relativeSeek));
        while (durable != null) {
            BytesKey durableKey = new BytesKey(relative(type, durable.key()));
            if (!overlay.containsKey(durableKey) || overlay.get(durableKey) != null) break;
            durable = ledger.snapshotFloorBefore(
                    subspace, full(relative(type, durable.key())));
        }
        Floor left = pending == null ? null : new Floor(
                Arrays.copyOfRange(pending.getKey().bytes, 1, pending.getKey().bytes.length),
                pending.getValue());
        Floor right = durable == null ? null : new Floor(durable.key(), durable.value());
        if (left == null) return right;
        if (right == null) return left;
        return compareUnsigned(left.key, right.key) >= 0 ? left : right;
    }

    private byte[] full(byte[] relative) {
        return ByteBuffer.allocate(storagePrefix.length + relative.length)
                .put(storagePrefix).put(relative).array();
    }

    private static byte[] relative(byte type, byte[] key) {
        return ByteBuffer.allocate(1 + key.length).put(type).put(key).array();
    }

    private final class CapturingBatch implements CommitBatch {
        private final Capture context;
        private final CommitConfig config;
        private final JmtAccessLease lease;
        private final Map<NodeKey, JmtNode> nodes = new LinkedHashMap<>();
        private final List<NodeKey> stale = new ArrayList<>();
        private final List<Value> values = new ArrayList<>();
        private byte[] root;
        private boolean closed;

        private CapturingBatch(Capture context, CommitConfig config, JmtAccessLease lease) {
            this.context = context;
            this.config = config;
            this.lease = lease;
        }
        @Override public void putNode(NodeKey key, JmtNode node) { open(); nodes.put(key, node); }
        @Override public void markStale(NodeKey key) { open(); stale.add(key); }
        @Override public void putValue(byte[] keyHash, byte[] value) {
            open(); requireHash(keyHash); values.add(new Value(keyHash, value));
        }
        @Override public void deleteValue(byte[] keyHash) {
            throw new UnsupportedOperationException("immutable snapshots never delete JMT values");
        }
        @Override public void setRootHash(byte[] rootHash) {
            open(); root = Objects.requireNonNull(rootHash, "rootHash").clone();
        }
        @Override public void commit() {
            open();
            try {
                if (root == null || root.length != HASH_BYTES) {
                    throw new IllegalStateException("snapshot JMT root must contain 32 bytes");
                }
                Optional<VersionedRoot> latest = latestRoot();
                if (!config.shouldApply(context.version, root, latest, rootHash(context.version))) {
                    throw new IllegalStateException("snapshot JMT version is already committed");
                }
                context.prepared = new Prepared(context.version, root, latest, nodes, stale, values);
            } finally { close(); }
        }
        @Override public void close() { if (!closed) { closed = true; lease.close(); } }
        private void open() { if (closed) throw new IllegalStateException("snapshot JMT batch closed"); }
    }

    private static final class Capture {
        private final long version;
        private Prepared prepared;
        private Capture(long version) { this.version = version; }
    }

    private record Prepared(long version, byte[] rootHash, Optional<VersionedRoot> expectedLatest,
                            Map<NodeKey, JmtNode> nodes, List<NodeKey> staleNodes,
                            List<Value> values) {
        private Prepared {
            rootHash = rootHash.clone();
            expectedLatest = expectedLatest.map(root -> new VersionedRoot(
                    root.version(), root.rootHash()));
            nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            staleNodes = List.copyOf(staleNodes);
            values = List.copyOf(values);
        }
    }

    private record Value(byte[] keyHash, byte[] value) {
        private Value { keyHash = keyHash.clone(); value = value.clone(); }
    }
    private record Floor(byte[] key, byte[] value) {
        private Floor { key = key.clone(); value = value.clone(); }
    }

    private static final class BytesKey implements Comparable<BytesKey> {
        private final byte[] bytes;
        private BytesKey(byte[] bytes) { this.bytes = bytes.clone(); }
        @Override public int compareTo(BytesKey other) { return compareUnsigned(bytes, other.bytes); }
        @Override public boolean equals(Object other) {
            return other instanceof BytesKey that && Arrays.equals(bytes, that.bytes);
        }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }

    private static boolean sameRoot(Optional<VersionedRoot> left, Optional<VersionedRoot> right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        return left.get().version() == right.get().version()
                && Arrays.equals(left.get().rootHash(), right.get().rootHash());
    }
    private static void requireHash(byte[] hash) {
        if (hash == null || hash.length != HASH_BYTES) {
            throw new IllegalArgumentException("JMT key hash must contain 32 bytes");
        }
    }
    private static void requireVersion(long version) {
        if (version < 0) throw new IllegalArgumentException("negative JMT version");
    }
    private static int comparePath(NibblePath left, NibblePath right) {
        int[] a = left.getNibbles(); int[] b = right.getNibbles();
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int compared = Integer.compare(a[i], b[i]);
            if (compared != 0) return compared;
        }
        return Integer.compare(a.length, b.length);
    }
    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int compared = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (compared != 0) return compared;
        }
        return Integer.compare(left.length, right.length);
    }
}
