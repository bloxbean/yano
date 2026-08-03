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
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Snapshot;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * CCL {@link JmtStore} over the app ledger's column families.
 *
 * <p>CCL calculates every node/value/stale mutation through this store, but
 * {@link CommitBatch#commit()} only freezes a prepared update. The runtime later
 * stages that update into the ledger's block {@link WriteBatch}; this is the
 * atomicity adapter required by ADR-025.</p>
 */
final class SharedRocksDbJmtStore implements JmtStore {
    static final byte[] CF_NODES = "jmt_nodes".getBytes(StandardCharsets.UTF_8);
    static final byte[] CF_VALUES = "jmt_values".getBytes(StandardCharsets.UTF_8);
    static final byte[] CF_ROOTS = "jmt_roots".getBytes(StandardCharsets.UTF_8);
    static final byte[] CF_STALE = "jmt_stale".getBytes(StandardCharsets.UTF_8);
    static final byte[] CF_METADATA = "jmt_metadata".getBytes(StandardCharsets.UTF_8);

    private static final byte[] LATEST_ROOT_KEY = "JMT_LATEST".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LATEST_VERSION_KEY = "JMT_VER".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FORMAT_DESCRIPTOR_KEY =
            "JMT_FORMAT".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PRUNE_WATERMARK_KEY =
            "JMT_PRUNE_WATERMARK".getBytes(StandardCharsets.US_ASCII);
    private static final int KEY_HASH_LENGTH = 32;
    private static final int VALUE_KEY_LENGTH = KEY_HASH_LENGTH + Long.BYTES;
    private static final byte VALUE_PRESENT = 1;
    private static final byte VALUE_TOMBSTONE = 0;

    private final RocksDB db;
    private final ColumnFamilyHandle nodesCf;
    private final ColumnFamilyHandle valuesCf;
    private final ColumnFamilyHandle rootsCf;
    private final ColumnFamilyHandle staleCf;
    private final ColumnFamilyHandle metadataCf;
    private final JmtAccessCoordinator accessCoordinator = new JmtAccessCoordinator();
    private final ThreadLocal<CaptureContext> capture = new ThreadLocal<>();

    private volatile JmtFormatDescriptor formatDescriptor;

    SharedRocksDbJmtStore(
            RocksDB db,
            ColumnFamilyHandle nodesCf,
            ColumnFamilyHandle valuesCf,
            ColumnFamilyHandle rootsCf,
            ColumnFamilyHandle staleCf,
            ColumnFamilyHandle metadataCf
    ) {
        this.db = Objects.requireNonNull(db, "db");
        this.nodesCf = Objects.requireNonNull(nodesCf, "nodesCf");
        this.valuesCf = Objects.requireNonNull(valuesCf, "valuesCf");
        this.rootsCf = Objects.requireNonNull(rootsCf, "rootsCf");
        this.staleCf = Objects.requireNonNull(staleCf, "staleCf");
        this.metadataCf = Objects.requireNonNull(metadataCf, "metadataCf");
        loadExistingFormat();
    }

    PreparedUpdate calculate(
            JellyfishMerkleTree tree,
            long version,
            Map<byte[], byte[]> updates
    ) {
        Objects.requireNonNull(tree, "tree");
        if (capture.get() != null) {
            throw new IllegalStateException("nested JMT preparation is not supported");
        }
        CaptureContext context = new CaptureContext(version);
        capture.set(context);
        try {
            JellyfishMerkleTree.CommitResult result = tree.put(version, updates);
            PreparedUpdate prepared = Objects.requireNonNull(
                    context.prepared, "CCL JMT commit did not produce a prepared update");
            if (result.version() != version
                    || !Arrays.equals(result.rootHash(), prepared.rootHash())) {
                throw new IllegalStateException("CCL JMT result differs from captured store batch");
            }
            return prepared;
        } finally {
            capture.remove();
        }
    }

    void stage(PreparedUpdate prepared, WriteBatch batch) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(batch, "batch");
        Optional<VersionedRoot> actualLatest = latestRoot();
        if (!sameRoot(prepared.expectedLatest(), actualLatest)) {
            throw new IllegalStateException("prepared JMT update is stale");
        }
        if (rootHash(prepared.version()).isPresent()) {
            throw new IllegalStateException(
                    "JMT version " + prepared.version() + " is already committed");
        }
        try {
            for (Map.Entry<NodeKey, JmtNode> entry : prepared.nodes().entrySet()) {
                batch.put(nodesCf, entry.getKey().toBytes(), entry.getValue().encode());
            }
            for (NodeKey staleNode : prepared.staleNodes()) {
                batch.put(staleCf, staleKey(prepared.version(), staleNode), new byte[0]);
            }
            for (ValueMutation value : prepared.values()) {
                batch.put(valuesCf, valueKey(value.keyHash(), prepared.version()),
                        value.delete() ? tombstoneValue() : encodeValue(value.value()));
            }
            byte[] versionBytes = versionKey(prepared.version());
            batch.put(rootsCf, versionBytes, prepared.rootHash());
            batch.put(rootsCf, LATEST_ROOT_KEY, prepared.rootHash());
            batch.put(rootsCf, LATEST_VERSION_KEY, versionBytes);
            batch.put(metadataCf, FORMAT_DESCRIPTOR_KEY,
                    JmtProfile.classicBlake2b256V1().format().encode());
        } catch (RocksDBException failure) {
            throw new RuntimeException("Failed to stage classic JMT update", failure);
        }
    }

    long pruneWatermark() {
        try {
            byte[] encoded = db.get(metadataCf, PRUNE_WATERMARK_KEY);
            if (encoded == null) {
                return -1;
            }
            if (encoded.length != Long.BYTES) {
                throw new JmtFormatMismatchException("Malformed JMT prune watermark");
            }
            long watermark = ByteBuffer.wrap(encoded).getLong();
            requireVersion(watermark);
            return watermark;
        } catch (RocksDBException failure) {
            throw new RuntimeException("Failed to read JMT prune watermark", failure);
        }
    }

    @Override
    public JmtAccessCoordinator accessCoordinator() {
        return accessCoordinator;
    }

    @Override
    public void ensureFormat(JmtFormatDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor").requirePersistent();
        try (JmtAccessLease ignored = accessCoordinator.tryAcquireMaintenance("ensureFormat")) {
            JmtFormatDescriptor classic = JmtProfile.classicBlake2b256V1().format();
            if (!classic.equals(descriptor)) {
                throw new JmtFormatMismatchException(
                        "Shared app ledger accepts only " + classic.profileId());
            }
            try {
                byte[] persisted = db.get(metadataCf, FORMAT_DESCRIPTOR_KEY);
                if (persisted == null) {
                    if (hasTreeData()) {
                        throw new JmtFormatMismatchException(
                                "Non-empty JMT column families have no format descriptor");
                    }
                    formatDescriptor = descriptor;
                    return;
                }
                JmtFormatDescriptor decoded = decodeFormat(persisted);
                if (!decoded.equals(descriptor)) {
                    throw new JmtFormatMismatchException(
                            "Persisted JMT format differs from configured profile");
                }
                formatDescriptor = decoded;
            } catch (RocksDBException failure) {
                throw new RuntimeException("Failed to validate JMT format", failure);
            }
        }
    }

    @Override
    public Optional<JmtFormatDescriptor> formatDescriptor() {
        return Optional.ofNullable(formatDescriptor);
    }

    @Override
    public JmtStoreInspection inspect(int maxRecords) {
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords must be > 0");
        }
        requireFormat();
        try (JmtAccessLease ignored = accessCoordinator.tryAcquireRead("inspect")) {
            Inspection inspection = new Inspection(maxRecords);
            Snapshot snapshot = db.getSnapshot();
            try (ReadOptions options = new ReadOptions()
                    .setSnapshot(snapshot)
                    .setTotalOrderSeek(true)) {
                inspectRoots(inspection, options);
                inspectLatest(inspection, options);
                inspectNodes(inspection, options);
                inspectValues(inspection, options);
                inspectStale(inspection, options);
                return inspection.toInspection();
            } catch (RocksDBException failure) {
                throw new RuntimeException("Failed to inspect shared JMT store", failure);
            } finally {
                db.releaseSnapshot(snapshot);
            }
        }
    }

    @Override
    public Optional<VersionedRoot> latestRoot() {
        try {
            byte[] root = db.get(rootsCf, LATEST_ROOT_KEY);
            byte[] version = db.get(rootsCf, LATEST_VERSION_KEY);
            if (root == null && version == null) {
                return Optional.empty();
            }
            if (root == null || version == null || version.length != Long.BYTES) {
                throw new JmtFormatMismatchException("Malformed JMT latest-root pointer");
            }
            long decodedVersion = ByteBuffer.wrap(version).getLong();
            requireVersion(decodedVersion);
            return Optional.of(new VersionedRoot(decodedVersion, root));
        } catch (RocksDBException failure) {
            throw new RuntimeException("Failed to read latest JMT root", failure);
        }
    }

    @Override
    public Optional<byte[]> rootHash(long version) {
        requireVersion(version);
        try {
            return Optional.ofNullable(db.get(rootsCf, versionKey(version)))
                    .map(byte[]::clone);
        } catch (RocksDBException failure) {
            throw new RuntimeException("Failed to read JMT root at version " + version, failure);
        }
    }

    @Override
    public Optional<NodeEntry> getNode(long version, NibblePath path) {
        requireVersion(version);
        Objects.requireNonNull(path, "path");
        byte[] seek = NodeKey.of(path, version).toBytes();
        try (ReadOptions options = new ReadOptions().setTotalOrderSeek(true);
             RocksIterator iterator = db.newIterator(nodesCf, options)) {
            for (iterator.seekForPrev(seek); iterator.isValid(); iterator.prev()) {
                NodeKey candidate = NodeKey.fromBytes(iterator.key());
                int pathComparison = comparePath(candidate.path(), path);
                if (pathComparison < 0) {
                    break;
                }
                if (pathComparison == 0 && candidate.version() <= version) {
                    return Optional.of(new NodeEntry(candidate,
                            JmtEncoding.decode(iterator.value())));
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public Optional<JmtNode> getNode(NodeKey nodeKey) {
        Objects.requireNonNull(nodeKey, "nodeKey");
        try {
            byte[] encoded = db.get(nodesCf, nodeKey.toBytes());
            return encoded != null
                    ? Optional.of(JmtEncoding.decode(encoded)) : Optional.empty();
        } catch (RocksDBException failure) {
            throw new RuntimeException("Failed to read exact JMT node", failure);
        }
    }

    @Override
    public Optional<byte[]> getValue(byte[] keyHash) {
        return latestRoot().flatMap(root -> getValueAt(keyHash, root.version()));
    }

    @Override
    public Optional<byte[]> getValueAt(byte[] keyHash, long version) {
        requireKeyHash(keyHash);
        requireVersion(version);
        byte[] seek = valueKey(keyHash, version);
        try (ReadOptions options = new ReadOptions().setTotalOrderSeek(true);
             RocksIterator iterator = db.newIterator(valuesCf, options)) {
            iterator.seekForPrev(seek);
            if (!iterator.isValid() || !hasKeyHash(iterator.key(), keyHash)) {
                return Optional.empty();
            }
            byte[] encoded = iterator.value();
            if (encoded.length == 0 || encoded[0] == VALUE_TOMBSTONE) {
                return Optional.empty();
            }
            if (encoded[0] != VALUE_PRESENT) {
                throw new JmtFormatMismatchException("Malformed JMT value record");
            }
            return Optional.of(Arrays.copyOfRange(encoded, 1, encoded.length));
        }
    }

    @Override
    public CommitBatch beginCommit(long version, CommitConfig config) {
        requireFormat();
        requireVersion(version);
        CaptureContext context = capture.get();
        if (context == null || context.version != version || context.prepared != null) {
            throw new IllegalStateException(
                    "JMT commits are accepted only inside a prepared calculation");
        }
        JmtAccessLease lease = accessCoordinator.tryAcquireUpdate("prepare-commit", version);
        return new CapturingCommitBatch(context, config, lease);
    }

    @Override
    public List<NodeKey> staleNodesUpTo(long versionInclusive) {
        requireVersion(versionInclusive);
        List<NodeKey> nodes = new ArrayList<>();
        try (ReadOptions options = new ReadOptions().setTotalOrderSeek(true);
             RocksIterator iterator = db.newIterator(staleCf, options)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length <= Long.BYTES) {
                    throw new JmtFormatMismatchException("Malformed JMT stale-node key");
                }
                long staleSince = ByteBuffer.wrap(key, 0, Long.BYTES).getLong();
                if (staleSince > versionInclusive) {
                    break;
                }
                nodes.add(NodeKey.fromBytes(Arrays.copyOfRange(
                        key, Long.BYTES, key.length)));
            }
        }
        return Collections.unmodifiableList(nodes);
    }

    @Override
    public int pruneUpTo(long versionInclusive) {
        requireFormat();
        requireVersion(versionInclusive);
        try (JmtAccessLease ignored = accessCoordinator.tryAcquireMaintenance(
                "pruneUpTo", versionInclusive)) {
            VersionedRoot latest = latestRoot().orElse(null);
            if (latest == null) {
                return 0;
            }
            if (versionInclusive > latest.version()) {
                throw new IllegalArgumentException(
                        "prune horizon exceeds latest JMT version " + latest.version());
            }
            return pruneUnderLease(versionInclusive);
        }
    }

    @Override
    public void close() {
        // The app ledger owns the RocksDB instance and column-family handles.
    }

    private int pruneUnderLease(long retainFrom) {
        int removed = 0;
        try (WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions().setSync(true)) {
            try (ReadOptions options = new ReadOptions().setTotalOrderSeek(true);
                 RocksIterator iterator = db.newIterator(staleCf, options)) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    byte[] staleKey = iterator.key();
                    if (staleKey.length <= Long.BYTES) {
                        throw new JmtFormatMismatchException("Malformed JMT stale-node key");
                    }
                    long staleSince = ByteBuffer.wrap(staleKey, 0, Long.BYTES).getLong();
                    if (staleSince > retainFrom) {
                        break;
                    }
                    byte[] nodeKey = Arrays.copyOfRange(
                            staleKey, Long.BYTES, staleKey.length);
                    batch.delete(nodesCf, nodeKey);
                    batch.delete(staleCf, staleKey);
                    removed += 2;
                }
            }
            try (ReadOptions options = new ReadOptions().setTotalOrderSeek(true);
                 RocksIterator iterator = db.newIterator(rootsCf, options)) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    byte[] key = iterator.key();
                    if (key.length != Long.BYTES) {
                        continue;
                    }
                    long version = ByteBuffer.wrap(key).getLong();
                    if (version < retainFrom) {
                        batch.delete(rootsCf, key);
                        removed++;
                    }
                }
            }
            removed += stageValuePruning(retainFrom, batch);
            batch.put(metadataCf, PRUNE_WATERMARK_KEY, versionKey(retainFrom));
            db.write(writeOptions, batch);
            return removed;
        } catch (RocksDBException failure) {
            throw new RuntimeException("Failed to prune shared JMT store", failure);
        }
    }

    private int stageValuePruning(long retainFrom, WriteBatch batch)
            throws RocksDBException {
        int removed = 0;
        try (ReadOptions options = new ReadOptions().setTotalOrderSeek(true);
             RocksIterator iterator = db.newIterator(valuesCf, options)) {
            byte[] currentHash = null;
            List<byte[]> older = new ArrayList<>();
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != VALUE_KEY_LENGTH) {
                    throw new JmtFormatMismatchException("Malformed JMT value key");
                }
                byte[] hash = Arrays.copyOf(key, KEY_HASH_LENGTH);
                if (currentHash == null || !Arrays.equals(currentHash, hash)) {
                    removed += deleteAllButLast(older, batch);
                    older.clear();
                    currentHash = hash;
                }
                long version = ByteBuffer.wrap(key, KEY_HASH_LENGTH, Long.BYTES).getLong();
                if (version <= retainFrom) {
                    older.add(key.clone());
                }
            }
            removed += deleteAllButLast(older, batch);
        }
        return removed;
    }

    private int deleteAllButLast(List<byte[]> keys, WriteBatch batch)
            throws RocksDBException {
        int removed = 0;
        for (int index = 0; index + 1 < keys.size(); index++) {
            batch.delete(valuesCf, keys.get(index));
            removed++;
        }
        return removed;
    }

    private void loadExistingFormat() {
        try {
            byte[] encoded = db.get(metadataCf, FORMAT_DESCRIPTOR_KEY);
            if (encoded != null) {
                formatDescriptor = decodeFormat(encoded);
            } else if (hasTreeData()) {
                throw new JmtFormatMismatchException(
                        "Non-empty JMT column families have no format descriptor");
            }
        } catch (RocksDBException failure) {
            throw new RuntimeException("Failed to load JMT format", failure);
        }
    }

    private JmtFormatDescriptor decodeFormat(byte[] encoded) {
        try {
            JmtFormatDescriptor descriptor = JmtFormatDescriptor.decode(encoded);
            descriptor.requirePersistent();
            return descriptor;
        } catch (IllegalArgumentException | JmtFormatMismatchException failure) {
            throw new JmtFormatMismatchException("Malformed JMT format descriptor", failure);
        }
    }

    private boolean hasTreeData() {
        return hasAny(nodesCf) || hasAny(valuesCf) || hasAny(rootsCf) || hasAny(staleCf);
    }

    private boolean hasAny(ColumnFamilyHandle handle) {
        try (ReadOptions options = new ReadOptions().setTotalOrderSeek(true);
             RocksIterator iterator = db.newIterator(handle, options)) {
            iterator.seekToFirst();
            return iterator.isValid();
        }
    }

    private void requireFormat() {
        if (formatDescriptor == null) {
            throw new JmtFormatMismatchException("JMT format is not initialized");
        }
    }

    private void inspectRoots(Inspection result, ReadOptions options) {
        try (RocksIterator iterator = db.newIterator(rootsCf, options)) {
            for (iterator.seekToFirst(); iterator.isValid() && !result.truncated;
                 iterator.next()) {
                byte[] key = iterator.key();
                if (key.length != Long.BYTES) {
                    if (!Arrays.equals(key, LATEST_ROOT_KEY)
                            && !Arrays.equals(key, LATEST_VERSION_KEY)) {
                        result.backendIssues.add("Malformed JMT root key");
                    }
                    continue;
                }
                if (!result.take()) {
                    break;
                }
                result.roots.add(new VersionedRoot(
                        ByteBuffer.wrap(key).getLong(), iterator.value()));
            }
        }
    }

    private void inspectLatest(Inspection result, ReadOptions options)
            throws RocksDBException {
        byte[] root = db.get(rootsCf, options, LATEST_ROOT_KEY);
        byte[] version = db.get(rootsCf, options, LATEST_VERSION_KEY);
        if (root == null && version == null) {
            return;
        }
        if (root == null || version == null || version.length != Long.BYTES) {
            result.backendIssues.add("Malformed JMT latest-root pointer");
            return;
        }
        result.latest = new VersionedRoot(ByteBuffer.wrap(version).getLong(), root);
    }

    private void inspectNodes(Inspection result, ReadOptions options) {
        try (RocksIterator iterator = db.newIterator(nodesCf, options)) {
            for (iterator.seekToFirst(); iterator.isValid() && !result.truncated;
                 iterator.next()) {
                if (!result.take()) {
                    break;
                }
                try {
                    result.nodes.add(new JmtStoreInspection.NodeRecord(
                            NodeKey.fromBytes(iterator.key()),
                            JmtEncoding.decode(iterator.value())));
                } catch (RuntimeException failure) {
                    result.backendIssues.add("Malformed JMT node: " + failure.getMessage());
                }
            }
        }
    }

    private void inspectValues(Inspection result, ReadOptions options) {
        try (RocksIterator iterator = db.newIterator(valuesCf, options)) {
            for (iterator.seekToFirst(); iterator.isValid() && !result.truncated;
                 iterator.next()) {
                if (!result.take()) {
                    break;
                }
                byte[] key = iterator.key();
                byte[] encoded = iterator.value();
                if (key.length != VALUE_KEY_LENGTH || encoded.length == 0
                        || (encoded[0] != VALUE_PRESENT && encoded[0] != VALUE_TOMBSTONE)) {
                    result.backendIssues.add("Malformed JMT value record");
                    continue;
                }
                byte[] value = encoded[0] == VALUE_PRESENT
                        ? Arrays.copyOfRange(encoded, 1, encoded.length) : null;
                result.values.add(new JmtStoreInspection.ValueRecord(
                        Arrays.copyOf(key, KEY_HASH_LENGTH),
                        ByteBuffer.wrap(key, KEY_HASH_LENGTH, Long.BYTES).getLong(),
                        value, encoded[0] == VALUE_TOMBSTONE));
            }
        }
    }

    private void inspectStale(Inspection result, ReadOptions options) {
        try (RocksIterator iterator = db.newIterator(staleCf, options)) {
            for (iterator.seekToFirst(); iterator.isValid() && !result.truncated;
                 iterator.next()) {
                if (!result.take()) {
                    break;
                }
                byte[] key = iterator.key();
                try {
                    if (key.length <= Long.BYTES) {
                        throw new IllegalArgumentException("truncated stale key");
                    }
                    result.stale.add(new JmtStoreInspection.StaleRecord(
                            ByteBuffer.wrap(key, 0, Long.BYTES).getLong(),
                            NodeKey.fromBytes(Arrays.copyOfRange(
                                    key, Long.BYTES, key.length))));
                } catch (RuntimeException failure) {
                    result.backendIssues.add("Malformed JMT stale record: "
                            + failure.getMessage());
                }
            }
        }
    }

    private final class CapturingCommitBatch implements CommitBatch {
        private final CaptureContext context;
        private final CommitConfig config;
        private final JmtAccessLease lease;
        private final LinkedHashMap<NodeKey, JmtNode> nodes = new LinkedHashMap<>();
        private final List<NodeKey> staleNodes = new ArrayList<>();
        private final List<ValueMutation> values = new ArrayList<>();
        private byte[] rootHash;
        private boolean closed;

        private CapturingCommitBatch(
                CaptureContext context,
                CommitConfig config,
                JmtAccessLease lease
        ) {
            this.context = context;
            this.config = Objects.requireNonNull(config, "config");
            this.lease = lease;
        }

        @Override
        public void putNode(NodeKey nodeKey, JmtNode node) {
            ensureOpen();
            nodes.put(Objects.requireNonNull(nodeKey, "nodeKey"),
                    Objects.requireNonNull(node, "node"));
        }

        @Override
        public void markStale(NodeKey nodeKey) {
            ensureOpen();
            staleNodes.add(Objects.requireNonNull(nodeKey, "nodeKey"));
        }

        @Override
        public void putValue(byte[] keyHash, byte[] value) {
            ensureOpen();
            values.add(new ValueMutation(keyHash, value, false));
        }

        @Override
        public void deleteValue(byte[] keyHash) {
            ensureOpen();
            values.add(new ValueMutation(keyHash, null, true));
        }

        @Override
        public void setRootHash(byte[] rootHash) {
            ensureOpen();
            this.rootHash = Objects.requireNonNull(rootHash, "rootHash").clone();
        }

        @Override
        public void commit() {
            ensureOpen();
            try {
                if (rootHash == null || rootHash.length != 32) {
                    throw new IllegalStateException("prepared JMT root must contain 32 bytes");
                }
                Optional<VersionedRoot> latest = latestRoot();
                Optional<byte[]> existing = rootHash(context.version);
                if (!config.shouldApply(context.version, rootHash, latest, existing)) {
                    throw new IllegalStateException(
                            "candidate JMT version is already committed");
                }
                context.prepared = new PreparedUpdate(
                        context.version, rootHash, latest, nodes,
                        staleNodes, values);
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lease.close();
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("capturing JMT batch is closed");
            }
        }
    }

    record PreparedUpdate(
            long version,
            byte[] rootHash,
            Optional<VersionedRoot> expectedLatest,
            Map<NodeKey, JmtNode> nodes,
            List<NodeKey> staleNodes,
            List<ValueMutation> values
    ) {
        PreparedUpdate {
            requireVersion(version);
            rootHash = Objects.requireNonNull(rootHash, "rootHash").clone();
            if (rootHash.length != 32) {
                throw new IllegalArgumentException("JMT root must contain 32 bytes");
            }
            expectedLatest = copyRoot(expectedLatest);
            nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            staleNodes = Collections.unmodifiableList(new ArrayList<>(staleNodes));
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }

        @Override public byte[] rootHash() { return rootHash.clone(); }
        @Override public Optional<VersionedRoot> expectedLatest() {
            return copyRoot(expectedLatest);
        }
    }

    record ValueMutation(byte[] keyHash, byte[] value, boolean delete) {
        ValueMutation {
            requireKeyHash(keyHash);
            keyHash = keyHash.clone();
            value = value != null ? value.clone() : null;
            if (delete == (value != null)) {
                throw new IllegalArgumentException("JMT value mutation delete/value differ");
            }
        }

        @Override public byte[] keyHash() { return keyHash.clone(); }
        @Override public byte[] value() { return value != null ? value.clone() : null; }
    }

    private static final class CaptureContext {
        private final long version;
        private PreparedUpdate prepared;

        private CaptureContext(long version) {
            this.version = version;
        }
    }

    private static final class Inspection {
        private final int limit;
        private int records;
        private boolean truncated;
        private VersionedRoot latest;
        private final List<VersionedRoot> roots = new ArrayList<>();
        private final List<JmtStoreInspection.NodeRecord> nodes = new ArrayList<>();
        private final List<JmtStoreInspection.ValueRecord> values = new ArrayList<>();
        private final List<JmtStoreInspection.StaleRecord> stale = new ArrayList<>();
        private final List<String> backendIssues = new ArrayList<>();

        private Inspection(int limit) {
            this.limit = limit;
        }

        private boolean take() {
            if (records >= limit) {
                truncated = true;
                return false;
            }
            records++;
            return true;
        }

        private JmtStoreInspection toInspection() {
            return new JmtStoreInspection(
                    roots, latest, nodes, values, stale, backendIssues, truncated);
        }
    }

    private static Optional<VersionedRoot> copyRoot(Optional<VersionedRoot> root) {
        return Objects.requireNonNull(root, "root")
                .map(value -> new VersionedRoot(value.version(), value.rootHash()));
    }

    private static boolean sameRoot(
            Optional<VersionedRoot> left,
            Optional<VersionedRoot> right
    ) {
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        return left.get().version() == right.get().version()
                && Arrays.equals(left.get().rootHash(), right.get().rootHash());
    }

    private static byte[] valueKey(byte[] keyHash, long version) {
        requireKeyHash(keyHash);
        requireVersion(version);
        return ByteBuffer.allocate(VALUE_KEY_LENGTH)
                .put(keyHash).putLong(version).array();
    }

    private static boolean hasKeyHash(byte[] key, byte[] keyHash) {
        if (key.length != VALUE_KEY_LENGTH) {
            return false;
        }
        for (int index = 0; index < KEY_HASH_LENGTH; index++) {
            if (key[index] != keyHash[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodeValue(byte[] value) {
        byte[] source = Objects.requireNonNull(value, "value");
        byte[] encoded = new byte[source.length + 1];
        encoded[0] = VALUE_PRESENT;
        System.arraycopy(source, 0, encoded, 1, source.length);
        return encoded;
    }

    private static byte[] tombstoneValue() {
        return new byte[]{VALUE_TOMBSTONE};
    }

    private static byte[] staleKey(long version, NodeKey nodeKey) {
        byte[] encodedNode = Objects.requireNonNull(nodeKey, "nodeKey").toBytes();
        return ByteBuffer.allocate(Long.BYTES + encodedNode.length)
                .putLong(version).put(encodedNode).array();
    }

    private static byte[] versionKey(long version) {
        requireVersion(version);
        return ByteBuffer.allocate(Long.BYTES).putLong(version).array();
    }

    private static int comparePath(NibblePath left, NibblePath right) {
        int[] leftNibbles = left.getNibbles();
        int[] rightNibbles = right.getNibbles();
        int length = Math.min(leftNibbles.length, rightNibbles.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(leftNibbles[index], rightNibbles[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftNibbles.length, rightNibbles.length);
    }

    private static void requireKeyHash(byte[] keyHash) {
        Objects.requireNonNull(keyHash, "keyHash");
        if (keyHash.length != KEY_HASH_LENGTH) {
            throw new IllegalArgumentException("JMT key hash must contain 32 bytes");
        }
    }

    private static void requireVersion(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("JMT version must be nonnegative");
        }
    }
}
