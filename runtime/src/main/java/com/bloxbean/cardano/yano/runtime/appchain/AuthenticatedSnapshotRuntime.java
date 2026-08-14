package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.yano.api.appchain.AppStateCapabilities;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotPlanCollector;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSourceCommitmentV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotBuildReceiptV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorDraftV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotEntry;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotHeadV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSeriesHandle;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import org.rocksdb.WriteBatch;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Consensus executor for the generic authenticated-snapshots-v1 capability. */
final class AuthenticatedSnapshotRuntime {
    private static final byte[] EMPTY_ROOT = new byte[32];
    private static final byte[] IDENTITY_KEY =
            "~snapshot/identity-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PRIMARY_NAMESPACE =
            "snapshots/v1/".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STORAGE_DOMAIN =
            "yano-authenticated-snapshot-storage-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IDENTITY_DOMAIN =
            "yano-authenticated-snapshot-identity-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String DISPUTE_META_KEY = "authenticated_snapshot_dispute_v1";
    private static final long ON_CHAIN_NODES_PER_ENTRY_LIMIT = 4;
    private static final long ON_CHAIN_NODE_BYTES_LIMIT = 4 * 1024;

    private final AppLedgerStore ledger;
    private final StateCommitmentIdentity primaryIdentity;
    private final AuthenticatedSnapshotSettings settings;
    private final Map<String, AuthenticatedSnapshotSeriesDescriptorV1> declarations;
    private final Map<String, AuthenticatedSnapshotSourceCommitmentV1> sourceCommitments;
    private final String archiveChainNamespace;
    private final Map<StorageKey, ReentrantReadWriteLock> leases = new ConcurrentHashMap<>();
    private final Map<StorageKey, Object> archiveMonitors = new ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore proofPermits;

    static Optional<AuthenticatedSnapshotRuntime> create(
            AppLedgerStore ledger,
            StateCommitmentIdentity primaryIdentity,
            AuthenticatedSnapshotSettings settings,
            List<AuthenticatedSnapshotSeriesDescriptorV1> declared,
            List<AuthenticatedSnapshotSourceCommitmentV1> sourceCommitments,
            boolean l1ProofRequired,
            String chainId) {
        if (!settings.enabled()) return Optional.empty();
        List<AuthenticatedSnapshotSeriesDescriptorV1> selected = settings.select(
                declared, primaryIdentity.profile(), l1ProofRequired);
        return Optional.of(new AuthenticatedSnapshotRuntime(
                ledger, primaryIdentity, settings, selected, sourceCommitments, chainId));
    }

    private AuthenticatedSnapshotRuntime(
            AppLedgerStore ledger,
            StateCommitmentIdentity primaryIdentity,
            AuthenticatedSnapshotSettings settings,
            List<AuthenticatedSnapshotSeriesDescriptorV1> declarations,
            List<AuthenticatedSnapshotSourceCommitmentV1> sourceCommitments,
            String chainId) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.primaryIdentity = Objects.requireNonNull(primaryIdentity, "primaryIdentity");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.archiveChainNamespace = java.util.HexFormat.of().formatHex(sha256().digest(
                Objects.requireNonNull(chainId, "chainId").getBytes(StandardCharsets.UTF_8)));
        this.proofPermits = new java.util.concurrent.Semaphore(settings.proofConcurrency(), true);
        Map<String, AuthenticatedSnapshotSeriesDescriptorV1> values = new LinkedHashMap<>();
        declarations.forEach(value -> values.put(value.seriesId(), value));
        this.declarations = Map.copyOf(values);
        Map<String, AuthenticatedSnapshotSourceCommitmentV1> commitments = new LinkedHashMap<>();
        for (AuthenticatedSnapshotSourceCommitmentV1 source : sourceCommitments) {
            if (commitments.putIfAbsent(source.seriesId(), source) != null) {
                throw new IllegalArgumentException(
                        "duplicate authenticated snapshot source verifier: " + source.seriesId());
            }
        }
        for (AuthenticatedSnapshotSeriesDescriptorV1 declaration : declarations) {
            AuthenticatedSnapshotSourceCommitmentV1 source = commitments.get(declaration.seriesId());
            if (source == null
                    || !declaration.sourceCommitmentAlgorithm().equals(source.algorithm())
                    || !declaration.sourceCommitmentWireVersion().equals(source.wireVersion())) {
                throw new IllegalArgumentException(
                        "authenticated snapshot series requires one matching source verifier: "
                                + declaration.seriesId());
            }
        }
        this.sourceCommitments = Map.copyOf(commitments);
        reconcileInterruptedLifecycle();
    }

    private void reconcileInterruptedLifecycle() {
        long height = ledger.tipHeight();
        if (height <= 0) return;
        for (var declaration : declarations.values()) {
            Optional<SnapshotHeadV1> head = ledger.stateBackend().get(
                    height, headKey(declaration.seriesId())).map(SnapshotCanonicalCodec::decodeHead);
            if (head.isEmpty()) continue;
            for (long sequence = 0; sequence <= head.orElseThrow().sequence(); sequence++) {
                StorageKey storage = storageKey(declaration, sequence);
                byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                if (lifecycle != null && "RESTORING".equals(ascii(lifecycle))) {
                    ledger.evictSnapshotNodes(storage.prefix());
                }
            }
        }
    }

    BlockSession beginBlock(AppStateWriter delegate) {
        AuthenticatedSnapshotPlanCollector collector = new AuthenticatedSnapshotPlanCollector(
                settings.maxOperationsPerBlock(), settings.maxBytesPerBlock());
        Map<String, SnapshotSeriesHandle> handles = new LinkedHashMap<>();
        declarations.forEach((id, descriptor) ->
                handles.put(id, new SnapshotSeriesHandle(descriptor, collector)));
        AppStateCapabilities capabilities = AppStateCapabilities.enabled(handles, collector);
        return new BlockSession(delegate, collector, capabilities);
    }

    Optional<byte[]> proof(SnapshotDescriptorV1 snapshot, byte[] canonicalKey) {
        AuthenticatedSnapshotSeriesDescriptorV1 declaration = declarations.get(snapshot.seriesId());
        if (declaration == null || snapshot.sequence() < 0) return Optional.empty();
        StorageKey storage = requireStorage(snapshot);
        if (StateCommitmentProfiles.CLASSIC_JMT.id().equals(snapshot.snapshotProfile())) {
            SnapshotJmtStore store = snapshotJmtStore(storage);
            JellyfishMerkleTree tree = new JellyfishMerkleTree(
                    store, JmtProfile.classicBlake2b256V1());
            return tree.getProofWire(canonicalKey, store.latestVersion());
        }
        NodeStore durable = new NodeStore() {
            @Override public byte[] get(byte[] key) {
                return ledger.snapshotNode(prefixed(storage.prefix(), key));
            }
            @Override public void put(byte[] key, byte[] value) { throw new UnsupportedOperationException(); }
            @Override public void delete(byte[] key) { throw new UnsupportedOperationException(); }
        };
        MpfTrie trie = new MpfTrie(durable, snapshot.snapshotRoot());
        return trie.getProofWire(Objects.requireNonNull(canonicalKey, "canonicalKey"));
    }

    Optional<byte[]> value(SnapshotDescriptorV1 snapshot, byte[] canonicalKey) {
        AuthenticatedSnapshotSeriesDescriptorV1 declaration = declarations.get(snapshot.seriesId());
        if (declaration == null) return Optional.empty();
        StorageKey storage = requireStorage(snapshot);
        if (StateCommitmentProfiles.CLASSIC_JMT.id().equals(snapshot.snapshotProfile())) {
            SnapshotJmtStore store = snapshotJmtStore(storage);
            JellyfishMerkleTree tree = new JellyfishMerkleTree(
                    store, JmtProfile.classicBlake2b256V1());
            return tree.get(canonicalKey, store.latestVersion());
        }
        NodeStore durable = new NodeStore() {
            @Override public byte[] get(byte[] key) {
                return ledger.snapshotNode(prefixed(storage.prefix(), key));
            }
            @Override public void put(byte[] key, byte[] value) { throw new UnsupportedOperationException(); }
            @Override public void delete(byte[] key) { throw new UnsupportedOperationException(); }
        };
        return Optional.ofNullable(new MpfTrie(durable, snapshot.snapshotRoot()).get(canonicalKey));
    }

    Optional<StateProof> stateProof(SnapshotDescriptorV1 snapshot, byte[] canonicalKey) {
        return withProofPermit(() -> stateProofAdmitted(snapshot, canonicalKey));
    }

    <T> T withProofPermit(java.util.function.Supplier<T> operation) {
        if (!proofPermits.tryAcquire()) {
            throw new java.util.concurrent.RejectedExecutionException(
                    "authenticated snapshot proof service is saturated");
        }
        try {
            return operation.get();
        } finally {
            proofPermits.release();
        }
    }

    Optional<StateProof> stateProofAdmitted(SnapshotDescriptorV1 snapshot, byte[] canonicalKey) {
            requireNotDisputed();
            AuthenticatedSnapshotSeriesDescriptorV1 declaration = declarations.get(snapshot.seriesId());
            if (declaration == null) return Optional.empty();
            StorageKey storage = requireStorage(snapshot);
            ReentrantReadWriteLock.ReadLock lease = lease(storage).readLock();
            if (!lease.tryLock()) {
                throw new java.util.concurrent.RejectedExecutionException(
                        "authenticated snapshot lifecycle operation is active");
            }
            try {
                byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                String lifecycleState = lifecycle != null ? ascii(lifecycle) : "";
                if (!"ONLINE".equals(lifecycleState) && !"ARCHIVED_VERIFIED".equals(lifecycleState)) {
                    return Optional.empty();
                }
                byte[] key = Objects.requireNonNull(canonicalKey, "canonicalKey").clone();
                Optional<byte[]> wire = proof(snapshot, key);
                if (wire.isEmpty()) return Optional.empty();
                byte[] value = value(snapshot, key).orElse(null);
                var identity = StateCommitmentIdentity.explicit(
                        StateCommitmentProfiles.require(snapshot.snapshotProfile()), storage.prefix());
                return Optional.of(new StateProof(
                        new StateSnapshot(identity, snapshot.completedAppChainHeight(), snapshot.snapshotRoot()),
                        key, value, value != null ? StateProof.Presence.PRESENT : StateProof.Presence.ABSENT,
                        identity.profile().proofEncodingId(), wire.orElseThrow()));
            } finally {
                lease.unlock();
            }
    }

    Path archive(SnapshotDescriptorV1 descriptor, String requestedName) {
        requireNotDisputed();
        StorageKey storage = requireStorage(descriptor);
        Path target = archiveTarget(descriptor, requestedName);
        synchronized (archiveMonitors.computeIfAbsent(storage, ignored -> new Object())) {
            ReentrantReadWriteLock.WriteLock writeLease = lease(storage).writeLock();
            writeLease.lock();
            try {
                byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                if (lifecycle == null || "ARCHIVED_ONLY".equals(ascii(lifecycle))) {
                    throw new IllegalStateException("snapshot is not online");
                }
                if (!Files.exists(target)) pruneMpfSnapshotIfEnabled(descriptor, storage);
            } finally {
                writeLease.unlock();
            }

            ReentrantReadWriteLock.ReadLock readLease = lease(storage).readLock();
            readLease.lock();
            try {
                byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                if (lifecycle == null || "ARCHIVED_ONLY".equals(ascii(lifecycle))) {
                    throw new IllegalStateException("snapshot is not online");
                }
                verifyOnline(descriptor, storage);
                if (Files.exists(target)) {
                    readArchive(target, descriptor, storage, true, (ignoredKey, ignoredValue) -> { });
                } else {
                    exportArchive(descriptor, storage, target);
                }
            } finally {
                readLease.unlock();
            }

            writeLease.lock();
            try {
                byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                if (lifecycle == null) throw new IllegalStateException("snapshot lifecycle is missing");
                if (!"ARCHIVED_ONLY".equals(ascii(lifecycle))) {
                    ledger.updateSnapshotLifecycle(storage.prefix(), "ARCHIVED_VERIFIED");
                }
            } finally {
                writeLease.unlock();
            }
            return target;
        }
    }

    private void exportArchive(SnapshotDescriptorV1 descriptor, StorageKey storage, Path target) {
        SnapshotMpfIntegrity.Result mpfIntegrity = StateCommitmentProfiles.MPF.id().equals(
                descriptor.snapshotProfile()) ? verifyMpfIntegrity(descriptor, storage) : null;
        AppLedgerStore.SnapshotNodeStats stats = mpfIntegrity != null
                ? new AppLedgerStore.SnapshotNodeStats(mpfIntegrity.nodeCount(), mpfIntegrity.bytes())
                : ledger.forEachSnapshotNode(storage.prefix(), settings.archiveMaxNodes(),
                settings.archiveMaxBytes(), (ignoredKey, ignoredValue) -> { });
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-"
                + java.util.UUID.randomUUID());
        try {
            writeArchive(temporary, descriptor, storage, stats,
                    mpfIntegrity == null ? ignored -> true : mpfIntegrity::contains);
            forceFile(temporary);
            readArchive(temporary, descriptor, storage, true, (ignoredKey, ignoredValue) -> { });
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                try {
                    Files.move(temporary, target);
                } catch (IOException failure) {
                    throw new RuntimeException("Failed to publish snapshot archive", failure);
                }
            } catch (IOException failure) {
                throw new RuntimeException("Failed to publish snapshot archive", failure);
            }
            forceDirectory(target.getParent());
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { }
        }
    }

    void restore(SnapshotDescriptorV1 descriptor, String requestedName) {
        requireNotDisputed();
        StorageKey storage = requireStorage(descriptor);
        ReentrantReadWriteLock.WriteLock lease = lease(storage).writeLock();
        lease.lock();
        try {
            Path archive = archiveTarget(descriptor, requestedName);
            byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
            boolean online = lifecycle != null && ("ONLINE".equals(ascii(lifecycle))
                    || "ARCHIVED_VERIFIED".equals(ascii(lifecycle)));
            if (online) verifyOnline(descriptor, storage);
            readArchive(archive, descriptor, storage, online, (ignoredKey, ignoredValue) -> { });
            ledger.beginSnapshotRestore(storage.prefix());
            List<AppLedgerStore.SnapshotNodeRecord> batch = new ArrayList<>(4096);
            try {
                readArchive(archive, descriptor, storage, false, (key, value) -> {
                    batch.add(new AppLedgerStore.SnapshotNodeRecord(key, value));
                    if (batch.size() == 4096) {
                        ledger.importSnapshotNodeBatch(storage.prefix(), batch);
                        batch.clear();
                    }
                });
                if (!batch.isEmpty()) ledger.importSnapshotNodeBatch(storage.prefix(), batch);
                verifyOnline(descriptor, storage);
                ledger.updateSnapshotLifecycle(storage.prefix(), "ONLINE");
            } catch (RuntimeException failure) {
                try { ledger.evictSnapshotNodes(storage.prefix()); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } finally {
            lease.unlock();
        }
    }

    int evict(SnapshotDescriptorV1 descriptor) {
        requireNotDisputed();
        StorageKey storage = requireStorage(descriptor);
        ReentrantReadWriteLock.WriteLock lease = lease(storage).writeLock();
        lease.lock();
        try {
            byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
            if (lifecycle == null || !"ARCHIVED_VERIFIED".equals(ascii(lifecycle))) {
                throw new IllegalStateException("snapshot requires a verified archive before eviction");
            }
            verifyOnline(descriptor, storage);
            readArchive(archiveTarget(descriptor, null), descriptor, storage, true,
                    (ignoredKey, ignoredValue) -> { });
            return ledger.evictSnapshotNodes(storage.prefix());
        } finally {
            lease.unlock();
        }
    }

    Optional<SnapshotDescriptorV1> descriptor(String seriesId, long sequence, long height) {
        return descriptor(seriesId, sequence, height, false);
    }

    Optional<SnapshotDescriptorV1> descriptorForAdmin(String seriesId, long sequence, long height) {
        return descriptor(seriesId, sequence, height, true);
    }

    private Optional<SnapshotDescriptorV1> descriptor(
            String seriesId, long sequence, long height, boolean includePrivate) {
        AuthenticatedSnapshotSeriesDescriptorV1 declaration = declarations.get(seriesId);
        if (declaration == null || sequence < 0 || height <= 0
                || (!includePrivate && declaration.visibility()
                != AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC)) return Optional.empty();
        return ledger.stateBackend().get(height, descriptorKey(seriesId, sequence))
                .map(SnapshotCanonicalCodec::decodeDescriptor)
                .map(value -> {
                    requireStorage(value);
                    return value;
                });
    }

    com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotPage list(
            String seriesId, String encodedCursor, int limit, long tipHeight) {
        if (limit <= 0 || limit > 100 || tipHeight <= 0) {
            throw new IllegalArgumentException("invalid authenticated snapshot list bounds");
        }
        String filter = seriesId == null ? "" : seriesId.trim();
        if (!filter.isEmpty()) {
            AuthenticatedSnapshotSeriesDescriptorV1 selected = declarations.get(filter);
            if (selected == null || selected.visibility()
                    != AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC) {
                throw new IllegalArgumentException("unknown public authenticated snapshot series");
            }
        }
        CatalogCursor cursor = encodedCursor == null || encodedCursor.isBlank()
                ? firstCursor(filter, tipHeight) : decodeCursor(encodedCursor, filter, tipHeight);
        long height = cursor.height();
        List<String> selected = filter.isEmpty()
                ? declarations.values().stream().filter(value -> value.visibility()
                == AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC)
                .map(AuthenticatedSnapshotSeriesDescriptorV1::seriesId).sorted().toList()
                : List.of(filter);
        java.util.ArrayList<com.bloxbean.cardano.yano.api.appchain.snapshot
                .AuthenticatedSnapshotSummary> result = new java.util.ArrayList<>();
        for (String series : selected) {
            int seriesComparison = series.compareTo(cursor.seriesId());
            if (seriesComparison < 0) continue;
            AuthenticatedSnapshotSeriesDescriptorV1 declaration = declarations.get(series);
            if (declaration == null || declaration.visibility()
                    != AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC) continue;
            Optional<SnapshotHeadV1> head = ledger.stateBackend().get(height, headKey(series))
                    .map(SnapshotCanonicalCodec::decodeHead);
            if (head.isEmpty()) continue;
            long start = seriesComparison == 0 ? cursor.nextSequence() : 0;
            for (long sequence = start; sequence <= head.orElseThrow().sequence()
                    && result.size() < limit; sequence++) {
                descriptor(series, sequence, height).ifPresent(value -> {
                    StorageKey storage = storageKey(declarations.get(series), value.sequence());
                    byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                    String state = lifecycle == null ? "UNKNOWN"
                            : new String(lifecycle, StandardCharsets.US_ASCII);
                    result.add(new com.bloxbean.cardano.yano.api.appchain.snapshot
                            .AuthenticatedSnapshotSummary(series, value.sequence(), value.snapshotId(),
                            value.entryCount(), value.completedAppChainHeight(),
                            value.snapshotProfile(), state));
                });
            }
            if (result.size() == limit) break;
        }
        String next = null;
        if (result.size() == limit) {
            var last = result.getLast();
            long nextSequence = Math.addExact(last.sequence(), 1);
            boolean more = ledger.stateBackend().get(height, headKey(last.seriesId()))
                    .map(SnapshotCanonicalCodec::decodeHead)
                    .map(head -> nextSequence <= head.sequence()).orElse(false)
                    || selected.stream().anyMatch(candidate -> candidate.compareTo(last.seriesId()) > 0
                    && ledger.stateBackend().get(height, headKey(candidate)).isPresent());
            if (more) next = encodeCursor(new CatalogCursor(filter, height,
                    cursor.root(), last.seriesId(), nextSequence));
        }
        return new com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotPage(
                result, next, height, cursor.root());
    }

    private CatalogCursor firstCursor(String filter, long height) {
        byte[] root = ledger.stateBackend().snapshot(height).orElseThrow(() ->
                new IllegalStateException("authenticated snapshot catalog root is unavailable"))
                .stateRoot();
        String first = filter.isEmpty() ? declarations.values().stream()
                .filter(value -> value.visibility()
                        == AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC)
                .map(AuthenticatedSnapshotSeriesDescriptorV1::seriesId).sorted().findFirst()
                .orElse("") : filter;
        return new CatalogCursor(filter, height, root, first, 0);
    }

    private CatalogCursor decodeCursor(String encoded, String filter, long tipHeight) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)
                    || bytes.length > 512) throw new IllegalArgumentException("noncanonical cursor");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readUnsignedByte() != 1) throw new IllegalArgumentException("cursor version");
                long height = in.readLong();
                byte[] root = in.readNBytes(32);
                String cursorFilter = readCursorText(in);
                String cursorSeries = readCursorText(in);
                long nextSequence = in.readLong();
                if (in.read() != -1 || height <= 0 || height > tipHeight || nextSequence < 0
                        || root.length != 32 || !cursorFilter.equals(filter)) {
                    throw new IllegalArgumentException("cursor does not match this catalog request");
                }
                byte[] retained = ledger.stateBackend().snapshot(height).orElseThrow(() ->
                        new IllegalArgumentException("cursor state root is no longer retained"))
                        .stateRoot();
                if (!Arrays.equals(root, retained)) {
                    throw new IllegalArgumentException("cursor state root mismatch");
                }
                return new CatalogCursor(cursorFilter, height, root, cursorSeries, nextSequence);
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (IOException malformed) {
            throw new IllegalArgumentException("malformed authenticated snapshot cursor", malformed);
        }
    }

    private static String encodeCursor(CatalogCursor cursor) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeByte(1); out.writeLong(cursor.height()); out.write(cursor.root());
                writeCursorText(out, cursor.filter()); writeCursorText(out, cursor.seriesId());
                out.writeLong(cursor.nextSequence());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String readCursorText(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > 127) throw new IllegalArgumentException("cursor text exceeds limit");
        byte[] value = in.readNBytes(length);
        if (value.length != length) throw new IllegalArgumentException("truncated cursor");
        String text = new String(value, StandardCharsets.US_ASCII);
        if (!text.isEmpty() && !text.matches("[a-z0-9][a-z0-9._-]{0,126}")) {
            throw new IllegalArgumentException("invalid cursor identifier");
        }
        return text;
    }

    private static void writeCursorText(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        out.writeShort(encoded.length); out.write(encoded);
    }

    private record CatalogCursor(String filter, long height, byte[] root,
                                 String seriesId, long nextSequence) {
        private CatalogCursor { root = root.clone(); }
        @Override public byte[] root() { return root.clone(); }
    }

    Map<String, Object> status(long height) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("enabled", true);
        List<Map<String, Object>> publicSeries = declarations.values().stream()
                .filter(series -> series.visibility()
                        == AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC)
                .sorted(java.util.Comparator.comparing(
                        AuthenticatedSnapshotSeriesDescriptorV1::seriesId))
                .map(series -> seriesStatus(series, height)).toList();
        value.put("series", publicSeries.stream().map(item -> item.get("seriesId")).toList());
        value.put("seriesDetails", publicSeries);
        value.put("tipHeight", height);
        value.put("storage", "shared-online-rocksdb");
        value.put("proofMaxConcurrency", settings.proofConcurrency());
        value.put("proofAvailablePermits", proofPermits.availablePermits());
        value.put("disputed", disputed());
        if (disputed()) value.put("disputeReason", ledger.metaString(DISPUTE_META_KEY));
        value.put("mpfPruningEnabled", settings.mpfPruningEnabled());
        value.put("retentionEnabled", settings.retentionEnabled());
        value.put("keepOnlineCount", settings.keepOnlineCount());
        return Map.copyOf(value);
    }

    private Map<String, Object> seriesStatus(
            AuthenticatedSnapshotSeriesDescriptorV1 series, long height) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("seriesId", series.seriesId());
        value.put("schemaId", series.schemaId());
        value.put("trigger", series.trigger().name());
        value.put("profile", series.snapshotProfile());
        value.put("proofWireVersion", series.proofWireVersion());
        value.put("verificationTarget", series.verificationTarget().name());
        value.put("recoveryCoverage", series.recoveryCoverage().name());
        ledger.stateBackend().get(height, headKey(series.seriesId()))
                .map(SnapshotCanonicalCodec::decodeHead).ifPresent(head -> {
                    value.put("latestSequence", head.sequence());
                    value.put("latestDescriptorCommitmentHex",
                            com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                                    head.descriptorCommitment()));
                    StorageKey storage = storageKey(series, head.sequence());
                    byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                    value.put("latestLifecycle", lifecycle != null ? ascii(lifecycle) : "UNKNOWN");
                });
        return Map.copyOf(value);
    }

    boolean online(String seriesId, long sequence, long height) {
        if (disputed()) return false;
        Optional<SnapshotDescriptorV1> descriptor = descriptorForAdmin(seriesId, sequence, height);
        if (descriptor.isEmpty()) return false;
        StorageKey storage = requireStorage(descriptor.orElseThrow());
        byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
        return lifecycle != null && ("ONLINE".equals(ascii(lifecycle))
                || "ARCHIVED_VERIFIED".equals(ascii(lifecycle)));
    }

    Optional<SnapshotDescriptorV1> retentionCandidate(long height) {
        if (!settings.retentionEnabled()) return Optional.empty();
        for (var declaration : declarations.values().stream()
                .sorted(java.util.Comparator.comparing(
                        AuthenticatedSnapshotSeriesDescriptorV1::seriesId)).toList()) {
            Optional<SnapshotHeadV1> head = ledger.stateBackend().get(
                    height, headKey(declaration.seriesId())).map(SnapshotCanonicalCodec::decodeHead);
            if (head.isEmpty() || head.orElseThrow().sequence() < settings.keepOnlineCount()) continue;
            long through = head.orElseThrow().sequence() - settings.keepOnlineCount();
            for (long sequence = 0; sequence <= through; sequence++) {
                Optional<SnapshotDescriptorV1> descriptor = descriptorForAdmin(
                        declaration.seriesId(), sequence, height);
                if (descriptor.isEmpty()) continue;
                StorageKey storage = requireStorage(descriptor.orElseThrow());
                byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
                if (lifecycle != null && ("ONLINE".equals(ascii(lifecycle))
                        || settings.evictAfterArchive()
                        && "ARCHIVED_VERIFIED".equals(ascii(lifecycle)))) {
                    return descriptor;
                }
            }
        }
        return Optional.empty();
    }

    Optional<byte[]> value(String seriesId, long sequence, byte[] key, long height) {
        requireNotDisputed();
        SnapshotDescriptorV1 descriptor = descriptorForAdmin(seriesId, sequence, height)
                .orElseThrow(() -> new IllegalArgumentException("snapshot descriptor was not found"));
        StorageKey storage = requireStorage(descriptor);
        ReentrantReadWriteLock.ReadLock lease = lease(storage).readLock();
        if (!lease.tryLock()) {
            throw new IllegalStateException("authenticated snapshot is temporarily unavailable");
        }
        try {
            byte[] lifecycle = ledger.snapshotLifecycle(storage.prefix());
            if (lifecycle == null || !("ONLINE".equals(ascii(lifecycle))
                    || "ARCHIVED_VERIFIED".equals(ascii(lifecycle)))) {
                throw new IllegalStateException("authenticated snapshot is not local");
            }
            return value(descriptor, key);
        } finally {
            lease.unlock();
        }
    }

    void markDisputed(String reason) {
        String retained = reason == null || reason.isBlank()
                ? "L1_HISTORY_DISPUTED" : reason;
        ledger.metaPutBytesSync(DISPUTE_META_KEY,
                retained.getBytes(StandardCharsets.UTF_8));
    }

    private boolean disputed() {
        return ledger.metaBytes(DISPUTE_META_KEY) != null;
    }

    private void requireNotDisputed() {
        if (disputed()) {
            throw new com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotDisputedException(
                    "authenticated snapshot lineage is DISPUTED: "
                            + ledger.metaString(DISPUTE_META_KEY));
        }
    }

    final class BlockSession {
        private final AppStateWriter delegate;
        private final AuthenticatedSnapshotPlanCollector collector;
        private final AppStateCapabilities capabilities;
        private final Map<StorageKey, SnapshotOverlayNodeStore> stores = new LinkedHashMap<>();
        private final Map<StorageKey, JmtBuild> jmtBuilds = new LinkedHashMap<>();
        private long applicationOperations;
        private long applicationBytes;

        private BlockSession(AppStateWriter delegate,
                             AuthenticatedSnapshotPlanCollector collector,
                             AppStateCapabilities capabilities) {
            this.delegate = delegate;
            this.collector = collector;
            this.capabilities = capabilities;
        }

        AppStateWriter writer() {
            return new AppStateWriter() {
                @Override public AppStateCapabilities capabilities() { return capabilities; }
                @Override public void put(byte[] key, byte[] value) {
                    requireApplicationKey(key);
                    chargeApplication(key.length + Objects.requireNonNull(value, "value").length);
                    delegate.put(key, value);
                }
                @Override public void delete(byte[] key) {
                    requireApplicationKey(key);
                    chargeApplication(key.length);
                    delegate.delete(key);
                }
                @Override public Optional<byte[]> get(byte[] key) { return delegate.get(key); }
                @Override public byte[] stateRoot() { return delegate.stateRoot(); }
                @Override public long committedHeight() { return delegate.committedHeight(); }
            };
        }

        private void chargeApplication(long bytes) {
            applicationOperations = Math.addExact(applicationOperations, 1);
            applicationBytes = Math.addExact(applicationBytes, bytes);
            if (applicationOperations + collector.operationCount() > settings.maxOperationsPerBlock()
                    || applicationBytes + collector.byteCount() > settings.maxBytesPerBlock()) {
                throw new IllegalArgumentException(
                        "application and authenticated-snapshot plan exceed shared block limits");
            }
        }

        private void chargeRuntimeMutation(byte[] key, byte[] value) {
            chargeApplication(Math.addExact(key.length, value == null ? 0 : value.length));
        }

        private void requireApplicationKey(byte[] key) {
            Objects.requireNonNull(key, "key");
            if (startsWith(key, PRIMARY_NAMESPACE)) {
                throw new IllegalArgumentException(
                        "snapshots/v1/ is reserved for the authenticated-snapshot runtime");
            }
        }

        void execute(WriteBatch batch, long blockHeight) {
            if (applicationOperations + collector.operationCount() > settings.maxOperationsPerBlock()
                    || applicationBytes + collector.byteCount() > settings.maxBytesPerBlock()) {
                throw new IllegalArgumentException(
                        "application and authenticated-snapshot plan exceed shared block limits");
            }
            for (AuthenticatedSnapshotPlanCollector.Intent intent : collector.intents()) {
                verifyDeclared(intent.descriptor());
                if (intent instanceof AuthenticatedSnapshotPlanCollector.Begin begin) {
                    executeBegin(begin, blockHeight, batch);
                } else if (intent instanceof AuthenticatedSnapshotPlanCollector.AppendChunk chunk) {
                    executeChunk(chunk, batch);
                } else if (intent instanceof AuthenticatedSnapshotPlanCollector.Seal seal) {
                    executeSeal(seal, blockHeight, batch);
                }
            }
            stores.forEach((ignored, store) -> store.stage(batch));
            jmtBuilds.forEach((key, build) -> build.stage(key, batch));
        }

        private void executeBegin(AuthenticatedSnapshotPlanCollector.Begin begin, long blockHeight,
                                  WriteBatch batch) {
            if (begin.baseHeight() > blockHeight || begin.coveredThroughHeight() > blockHeight) {
                throw new IllegalArgumentException("snapshot coverage cannot exceed the applying block");
            }
            byte[] receiptKey = receiptKey(begin.descriptor().seriesId());
            if (delegate.get(receiptKey).isPresent()) {
                throw new IllegalStateException("snapshot series already has an active build");
            }
            Optional<SnapshotHeadV1> head = delegate.get(headKey(begin.descriptor().seriesId()))
                    .map(SnapshotCanonicalCodec::decodeHead);
            long expectedSequence = head.map(value -> Math.addExact(value.sequence(), 1)).orElse(0L);
            if (begin.sequence() != expectedSequence) {
                throw new IllegalArgumentException("snapshot sequence is not the next series sequence");
            }
            StorageKey key = storageKey(begin.descriptor(), begin.sequence());
            SnapshotDescriptorDraftV1 draft = new SnapshotDescriptorDraftV1(begin.descriptor(),
                    begin.sequence(), begin.snapshotId(), begin.boundary(), begin.baseHeight(),
                    begin.coveredFromHeight(), begin.coveredThroughHeight(), begin.sourceDatasetRoot(),
                    begin.expectedChunks(), begin.expectedEntries());
            byte[] sourceAccumulator = require32(
                    sourceCommitment(begin.descriptor()).initial(draft), "source accumulator");
            byte[] identity = identityLeaf(begin);
            chargeRuntimeMutation(IDENTITY_KEY, identity);
            byte[] root;
            if (StateCommitmentProfiles.CLASSIC_JMT.id().equals(begin.descriptor().snapshotProfile())) {
                JmtBuild build = jmtBuild(key);
                root = build.putBatch(Map.of(IDENTITY_KEY, identity));
            } else {
                SnapshotOverlayNodeStore store = store(key);
                MpfTrie trie = new MpfTrie(store);
                trie.put(IDENTITY_KEY, identity);
                root = normalizedRoot(trie.getRootHash());
            }
            SnapshotBuildReceiptV1 receipt = new SnapshotBuildReceiptV1(begin.sequence(),
                    begin.snapshotId(), begin.boundary(), begin.baseHeight(), begin.coveredFromHeight(),
                    begin.coveredThroughHeight(), begin.descriptor().recoveryCoverage(),
                    begin.descriptorDraftDigest(), begin.sourceDatasetRoot(),
                    begin.descriptor().sourceCommitmentAlgorithm(),
                    begin.descriptor().sourceCommitmentWireVersion(), begin.expectedChunks(), 0,
                    begin.expectedEntries(), 0, new byte[0], sourceAccumulator, root);
            byte[] encoded = SnapshotCanonicalCodec.encodeReceipt(receipt);
            chargeRuntimeMutation(receiptKey, encoded);
            delegate.put(receiptKey, encoded);
            ledger.stageSnapshotBuild(batch, key.prefix(), encoded);
        }

        private void executeChunk(AuthenticatedSnapshotPlanCollector.AppendChunk chunk,
                                  WriteBatch batch) {
            byte[] receiptKey = receiptKey(chunk.descriptor().seriesId());
            SnapshotBuildReceiptV1 receipt = delegate.get(receiptKey)
                    .map(SnapshotCanonicalCodec::decodeReceipt)
                    .orElseThrow(() -> new IllegalStateException("snapshot chunk has no active build"));
            requireMatching(receipt, chunk.sequence(), chunk.descriptorDraftDigest());
            if (chunk.chunkIndex() != receipt.nextChunk()) {
                throw new IllegalArgumentException("snapshot chunk is duplicate, missing, or reordered");
            }
            if (receipt.receivedEntries() > receipt.expectedEntries() - chunk.entries().size()) {
                throw new IllegalArgumentException("snapshot chunk exceeds expected entry count");
            }
            byte[] sourceAccumulator = require32(sourceCommitment(chunk.descriptor()).append(
                    receipt.sourceAccumulator(), chunk.chunkIndex(), chunk.entries()),
                    "source accumulator");
            StorageKey key = storageKey(chunk.descriptor(), chunk.sequence());
            byte[] last = receipt.lastApplicationKey();
            byte[] root;
            if (StateCommitmentProfiles.CLASSIC_JMT.id().equals(chunk.descriptor().snapshotProfile())) {
                JmtBuild build = jmtBuild(key);
                Map<byte[], byte[]> updates = new LinkedHashMap<>();
                for (SnapshotEntry entry : chunk.entries()) {
                    if (last.length > 0 && compareUnsigned(last, entry.key()) >= 0) {
                        throw new IllegalArgumentException("snapshot keys overlap or are not globally ordered");
                    }
                    updates.put(entry.key(), entry.value());
                    last = entry.key();
                }
                root = updates.isEmpty() ? receipt.partialRoot() : build.putBatch(updates);
            } else {
                SnapshotOverlayNodeStore store = store(key);
                MpfTrie trie = Arrays.equals(receipt.partialRoot(), EMPTY_ROOT)
                        ? new MpfTrie(store) : new MpfTrie(store, receipt.partialRoot());
                for (SnapshotEntry entry : chunk.entries()) {
                    if (last.length > 0 && compareUnsigned(last, entry.key()) >= 0) {
                        throw new IllegalArgumentException("snapshot keys overlap or are not globally ordered");
                    }
                    trie.put(entry.key(), entry.value());
                    last = entry.key();
                }
                root = normalizedRoot(trie.getRootHash());
            }
            SnapshotBuildReceiptV1 next = new SnapshotBuildReceiptV1(receipt.sequence(),
                    receipt.snapshotId(), receipt.sourceBoundary(), receipt.baseHeight(),
                    receipt.coveredFromHeight(), receipt.coveredThroughHeight(), receipt.recoveryCoverage(),
                    receipt.descriptorDraftDigest(), receipt.sourceDatasetRoot(),
                    receipt.sourceCommitmentAlgorithm(), receipt.sourceCommitmentWireVersion(),
                    receipt.expectedChunks(), Math.addExact(receipt.nextChunk(), 1),
                    receipt.expectedEntries(), Math.addExact(receipt.receivedEntries(), chunk.entries().size()),
                    last, sourceAccumulator, root);
            byte[] encoded = SnapshotCanonicalCodec.encodeReceipt(next);
            chargeRuntimeMutation(receiptKey, encoded);
            delegate.put(receiptKey, encoded);
            ledger.stageSnapshotBuild(batch, key.prefix(), encoded);
        }

        private void executeSeal(AuthenticatedSnapshotPlanCollector.Seal seal,
                                 long blockHeight, WriteBatch batch) {
            String series = seal.descriptor().seriesId();
            byte[] receiptKey = receiptKey(series);
            SnapshotBuildReceiptV1 receipt = delegate.get(receiptKey)
                    .map(SnapshotCanonicalCodec::decodeReceipt)
                    .orElseThrow(() -> new IllegalStateException("snapshot seal has no active build"));
            requireMatching(receipt, seal.sequence(), seal.descriptorDraftDigest());
            if (receipt.nextChunk() != receipt.expectedChunks()
                    || receipt.receivedEntries() != receipt.expectedEntries()) {
                throw new IllegalStateException("snapshot seal is incomplete");
            }
            byte[] verifiedSourceRoot = require32(sourceCommitment(seal.descriptor()).finish(
                    receipt.sourceAccumulator(), receipt.nextChunk(), receipt.receivedEntries()),
                    "verified source root");
            if (!Arrays.equals(verifiedSourceRoot, receipt.sourceDatasetRoot())) {
                throw new IllegalArgumentException("snapshot source dataset commitment mismatch");
            }
            SnapshotHeadV1 previousHead = delegate.get(headKey(series))
                    .map(SnapshotCanonicalCodec::decodeHead).orElse(null);
            byte[] previous = previousHead != null
                    ? previousHead.descriptorCommitment() : new byte[32];
            StorageKey storage = storageKey(seal.descriptor(), seal.sequence());
            byte[] snapshotRoot = StateCommitmentProfiles.CLASSIC_JMT.id().equals(
                    seal.descriptor().snapshotProfile())
                    ? jmtBuild(storage).root() : receipt.partialRoot();
            SnapshotDescriptorV1 descriptor = new SnapshotDescriptorV1(
                    primaryIdentity.genesisId(), primaryIdentity.digest(), series, receipt.sequence(),
                    receipt.snapshotId(), seal.descriptor().snapshotProfile(),
                    seal.descriptor().formatFingerprint(), seal.descriptor().proofWireVersion(),
                    snapshotRoot, receipt.sourceDatasetRoot(),
                    receipt.sourceCommitmentAlgorithm(), receipt.sourceCommitmentWireVersion(),
                    seal.descriptor().schemaId(), receipt.receivedEntries(), receipt.baseHeight(),
                    blockHeight, receipt.coveredFromHeight(), receipt.coveredThroughHeight(), previous,
                    receipt.sourceBoundary(), receipt.recoveryCoverage(), true);
            byte[] encodedDescriptor = SnapshotCanonicalCodec.encodeDescriptor(descriptor);
            if (seal.descriptor().verificationTarget()
                    == AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN) {
                if (encodedDescriptor.length > 4096) {
                    throw new IllegalArgumentException(
                            "on-chain authenticated snapshot descriptor exceeds 4096 bytes");
                }
                long consensusNodes = Math.addExact(1024L, Math.multiplyExact(
                        seal.descriptor().maxEntriesPerSnapshot() + 1,
                        ON_CHAIN_NODES_PER_ENTRY_LIMIT));
                long consensusBytes = Math.multiplyExact(
                        consensusNodes, ON_CHAIN_NODE_BYTES_LIMIT);
                SnapshotMpfIntegrity.Result integrity = new SnapshotMpfIntegrity(
                        store(storage)::get, consensusNodes, consensusBytes)
                        .verify(snapshotRoot);
                if (integrity.entryCount() != receipt.receivedEntries() + 1
                        || integrity.maximumFoldDepth() > 32) {
                    throw new IllegalArgumentException(
                            "on-chain authenticated snapshot exceeds the released 32-fold proof envelope");
                }
            }
            byte[] commitment = descriptor.commitment();
            chargeRuntimeMutation(descriptorKey(series, receipt.sequence()), encodedDescriptor);
            delegate.put(descriptorKey(series, receipt.sequence()),
                    encodedDescriptor);
            byte[] encodedHead = SnapshotCanonicalCodec.encodeHead(
                    new SnapshotHeadV1(receipt.sequence(), commitment));
            chargeRuntimeMutation(headKey(series), encodedHead);
            delegate.put(headKey(series), encodedHead);
            chargeRuntimeMutation(receiptKey, null);
            delegate.delete(receiptKey);

            ledger.stageSnapshotRoot(batch, storage.prefix(), encodedDescriptor);
            ledger.stageDeleteSnapshotBuild(batch, storage.prefix());
            ledger.stageSnapshotLifecycle(batch, storage.prefix(),
                    "ONLINE".getBytes(StandardCharsets.US_ASCII));
        }

        private SnapshotOverlayNodeStore store(StorageKey key) {
            return stores.computeIfAbsent(key, ignored -> new SnapshotOverlayNodeStore(key.prefix()));
        }

        private void verifyDeclared(AuthenticatedSnapshotSeriesDescriptorV1 descriptor) {
            AuthenticatedSnapshotSeriesDescriptorV1 expected = declarations.get(descriptor.seriesId());
            if (!descriptor.equals(expected)) {
                throw new IllegalArgumentException("snapshot intent uses an undeclared or incompatible series");
            }
            if (!StateCommitmentProfiles.MPF.id().equals(descriptor.snapshotProfile())
                    && !StateCommitmentProfiles.CLASSIC_JMT.id().equals(descriptor.snapshotProfile())) {
                throw new IllegalArgumentException("unsupported authenticated snapshot profile");
            }
        }

        private AuthenticatedSnapshotSourceCommitmentV1 sourceCommitment(
                AuthenticatedSnapshotSeriesDescriptorV1 descriptor) {
            AuthenticatedSnapshotSourceCommitmentV1 source = sourceCommitments.get(descriptor.seriesId());
            if (source == null || !descriptor.sourceCommitmentAlgorithm().equals(source.algorithm())
                    || !descriptor.sourceCommitmentWireVersion().equals(source.wireVersion())) {
                throw new IllegalStateException("snapshot source verifier is unavailable");
            }
            return source;
        }

        private JmtBuild jmtBuild(StorageKey key) {
            return jmtBuilds.computeIfAbsent(key, ignored -> new JmtBuild(key));
        }
    }

    private static byte[] require32(byte[] value, String name) {
        byte[] copy = Objects.requireNonNull(value, name).clone();
        if (copy.length != 32) throw new IllegalArgumentException(name + " must be 32 bytes");
        return copy;
    }

    private final class JmtBuild {
        private final SnapshotJmtStore store;
        private final JellyfishMerkleTree tree;

        private JmtBuild(StorageKey storage) {
            this.store = snapshotJmtStore(storage);
            this.tree = new JellyfishMerkleTree(store, JmtProfile.classicBlake2b256V1());
        }
        private byte[] putBatch(Map<byte[], byte[]> updates) { return store.putNext(tree, updates); }
        private byte[] root() { return store.latestRoot().orElseThrow().rootHash(); }
        private void stage(StorageKey storage, WriteBatch batch) {
            store.stage(batch);
        }
    }

    private SnapshotJmtStore snapshotJmtStore(StorageKey storage) {
        return new SnapshotJmtStore(ledger, storage.prefix(),
                Math.multiplyExact((long) settings.maxOperationsPerBlock(), 64),
                Math.multiplyExact(settings.maxBytesPerBlock(), 64),
                settings.archiveMaxBytes());
    }

    private final class SnapshotOverlayNodeStore implements NodeStore {
        private final byte[] prefix;
        private final Map<ByteKey, byte[]> mutations = new LinkedHashMap<>();
        private final long maximumMutations = Math.multiplyExact(
                (long) settings.maxOperationsPerBlock(), 64);
        private final long maximumBytes = Math.multiplyExact(settings.maxBytesPerBlock(), 64);
        private long mutationBytes;

        private SnapshotOverlayNodeStore(byte[] prefix) { this.prefix = prefix.clone(); }

        @Override public byte[] get(byte[] key) {
            byte[] pending = mutations.get(new ByteKey(key));
            if (pending != null) return pending.clone();
            return ledger.snapshotNode(prefixed(prefix, key));
        }

        @Override public void put(byte[] key, byte[] value) {
            ByteKey wrapped = new ByteKey(key);
            byte[] previous = mutations.put(wrapped, value.clone());
            mutationBytes = Math.addExact(mutationBytes, key.length + value.length
                    - (previous != null ? key.length + previous.length : 0));
            if (mutations.size() > maximumMutations || mutationBytes > maximumBytes) {
                throw new IllegalArgumentException("snapshot MPF generated-node budget exceeded");
            }
        }

        @Override public void delete(byte[] key) {
            throw new UnsupportedOperationException("sealed snapshot nodes are immutable");
        }

        private void stage(WriteBatch batch) {
            mutations.forEach((key, value) ->
                    ledger.stageSnapshotNode(batch, prefixed(prefix, key.bytes), value));
        }
    }

    private StorageKey storageKey(AuthenticatedSnapshotSeriesDescriptorV1 descriptor, long sequence) {
        byte[] series = descriptor.seriesId().getBytes(StandardCharsets.US_ASCII);
        byte[] material = ByteBuffer.allocate(STORAGE_DOMAIN.length + 64 + 8 + 2 + series.length)
                .put(STORAGE_DOMAIN).put(primaryIdentity.genesisId())
                .put(descriptor.formatFingerprint()).putLong(sequence).putShort((short) series.length)
                .put(series).array();
        return new StorageKey(Blake2bUtil.blake2bHash256(material));
    }

    private StorageKey requireStorage(SnapshotDescriptorV1 descriptor) {
        AuthenticatedSnapshotSeriesDescriptorV1 declaration = declarations.get(descriptor.seriesId());
        if (declaration == null
                || !Arrays.equals(primaryIdentity.genesisId(), descriptor.chainGenerationId())
                || !Arrays.equals(primaryIdentity.digest(), descriptor.applicationProfileDigest())
                || !declaration.schemaId().equals(descriptor.schemaId())
                || !declaration.snapshotProfile().equals(descriptor.snapshotProfile())
                || !Arrays.equals(declaration.formatFingerprint(),
                descriptor.snapshotFormatFingerprint())
                || !declaration.proofWireVersion().equals(descriptor.snapshotProofWireVersion())
                || !declaration.sourceCommitmentAlgorithm().equals(
                descriptor.sourceCommitmentAlgorithm())
                || !declaration.sourceCommitmentWireVersion().equals(
                descriptor.sourceCommitmentWireVersion())
                || declaration.recoveryCoverage() != descriptor.recoveryCoverage()
                || !descriptor.complete()) {
            throw new IllegalArgumentException("snapshot descriptor differs from the enabled series");
        }
        StorageKey storage = storageKey(declaration, descriptor.sequence());
        byte[] persisted = ledger.snapshotRootMetadata(storage.prefix());
        if (persisted == null || !Arrays.equals(SnapshotCanonicalCodec.encodeDescriptor(descriptor), persisted)) {
            throw new IllegalArgumentException("snapshot descriptor is not the committed storage descriptor");
        }
        return storage;
    }

    private ReentrantReadWriteLock lease(StorageKey storage) {
        return leases.computeIfAbsent(storage, ignored -> new ReentrantReadWriteLock(true));
    }

    private Path archiveTarget(SnapshotDescriptorV1 descriptor, String requestedName) {
        try {
            Path base = settings.archiveDirectory();
            Files.createDirectories(base);
            if (Files.isSymbolicLink(base)) {
                throw new IllegalArgumentException("snapshot archive directory must not be a symlink");
            }
            Path realBase = base.toRealPath();
            Path chainDirectory = realBase.resolve(archiveChainNamespace);
            Path generationDirectory = chainDirectory.resolve(
                    java.util.HexFormat.of().formatHex(descriptor.chainGenerationId()));
            Path profileDirectory = generationDirectory.resolve(
                    java.util.HexFormat.of().formatHex(descriptor.applicationProfileDigest()));
            Files.createDirectories(profileDirectory);
            if (Files.isSymbolicLink(chainDirectory) || Files.isSymbolicLink(generationDirectory)
                    || Files.isSymbolicLink(profileDirectory)) {
                throw new IllegalArgumentException("snapshot archive namespace must not contain symlinks");
            }
            Path realNamespace = profileDirectory.toRealPath();
            if (!realNamespace.startsWith(realBase)) {
                throw new IllegalArgumentException("snapshot archive namespace escapes its configured directory");
            }
            String fallback = descriptor.seriesId() + "-" + descriptor.sequence() + ".asv1";
            if (requestedName != null && !requestedName.isBlank()) {
                throw new IllegalArgumentException("custom snapshot archive names are not supported in v1");
            }
            String name = fallback;
            Path relative = Path.of(name);
            if (relative.isAbsolute()) throw new IllegalArgumentException("archivePath must be relative");
            Path target = realNamespace.resolve(relative).normalize();
            if (!target.startsWith(realNamespace) || target.equals(realNamespace)) {
                throw new IllegalArgumentException("archivePath escapes the configured archive directory");
            }
            Files.createDirectories(target.getParent());
            if (!target.getParent().toRealPath().equals(realNamespace)
                    || Files.isSymbolicLink(target.getParent())
                    || Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(target)) {
                throw new IllegalArgumentException("snapshot archive parent must not be a symlink");
            }
            return target;
        } catch (IOException failure) {
            throw new RuntimeException("Failed to prepare snapshot archive directory", failure);
        }
    }

    private void writeArchive(Path target, SnapshotDescriptorV1 descriptor, StorageKey storage,
                              AppLedgerStore.SnapshotNodeStats stats,
                              java.util.function.Predicate<byte[]> included) {
        byte[] descriptorBytes = SnapshotCanonicalCodec.encodeDescriptor(descriptor);
        MessageDigest digest = sha256();
        try (var raw = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
             var buffered = new BufferedOutputStream(raw);
             var digested = new java.security.DigestOutputStream(buffered, digest);
             var out = new DataOutputStream(digested)) {
            out.write("YANOASV1".getBytes(StandardCharsets.US_ASCII));
            out.writeInt(descriptorBytes.length);
            out.write(descriptorBytes);
            out.writeLong(stats.nodeCount());
            ledger.forEachSnapshotNode(storage.prefix(), physicalArchiveNodeLimit(),
                    physicalArchiveByteLimit(), (key, value) -> {
                        if (!included.test(key)) return;
                        try {
                            out.writeInt(key.length);
                            out.write(key);
                            out.writeInt(value.length);
                            out.write(value);
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    });
            out.flush();
            digested.on(false);
            out.write(digest.digest());
            out.flush();
        } catch (IOException | java.io.UncheckedIOException failure) {
            try { Files.deleteIfExists(target); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
            throw new RuntimeException("Failed to write authenticated snapshot archive", failure);
        }
    }

    private ArchiveEnvelope readArchive(Path path, SnapshotDescriptorV1 expected,
                                        StorageKey storage, boolean compareOnline,
                                        java.util.function.BiConsumer<byte[], byte[]> consumer) {
        SnapshotMpfIntegrity.Result expectedReachable = compareOnline
                && StateCommitmentProfiles.MPF.id().equals(expected.snapshotProfile())
                ? verifyMpfIntegrity(expected, storage) : null;
        try (var channel = Files.newByteChannel(path, java.util.Set.of(
                StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size <= 32 || size > settings.archiveMaxBytes() + 16L * 1024 * 1024) {
                throw new IllegalArgumentException("snapshot archive exceeds configured size limits");
            }
            MessageDigest digest = sha256();
            byte[] descriptorBytes;
            byte[] actualDigest;
            long count;
            long bytes = 0;
            try (var raw = java.nio.channels.Channels.newInputStream(channel);
                 var limited = new java.io.BufferedInputStream(raw);
                 var digested = new java.security.DigestInputStream(limited, digest);
                 var in = new DataInputStream(digested)) {
                byte[] magic = in.readNBytes(8);
                if (!Arrays.equals(magic, "YANOASV1".getBytes(StandardCharsets.US_ASCII))) {
                    throw new IllegalArgumentException("invalid snapshot archive magic");
                }
                int descriptorLength = in.readInt();
                if (descriptorLength <= 0 || descriptorLength > 64 * 1024) {
                    throw new IllegalArgumentException("invalid snapshot descriptor length");
                }
                descriptorBytes = in.readNBytes(descriptorLength);
                if (descriptorBytes.length != descriptorLength) throw new IllegalArgumentException("truncated archive");
                SnapshotDescriptorV1 descriptor = SnapshotCanonicalCodec.decodeDescriptor(descriptorBytes);
                if (!Arrays.equals(descriptor.commitment(), expected.commitment())) {
                    throw new IllegalArgumentException("snapshot archive descriptor mismatch");
                }
                count = in.readLong();
                if (count < 0 || count > settings.archiveMaxNodes()) {
                    throw new IllegalArgumentException("snapshot archive node count exceeds limit");
                }
                byte[] previousKey = null;
                for (long index = 0; index < count; index++) {
                    int keyLength = in.readInt();
                    if (keyLength <= 0 || keyLength > 1024) throw new IllegalArgumentException("invalid archive key");
                    byte[] key = in.readNBytes(keyLength);
                    int valueLength = in.readInt();
                    if (valueLength < 0 || valueLength > 16 * 1024 * 1024) {
                        throw new IllegalArgumentException("invalid archive node value");
                    }
                    byte[] value = in.readNBytes(valueLength);
                    if (key.length != keyLength || value.length != valueLength) {
                        throw new IllegalArgumentException("truncated archive node");
                    }
                    if (previousKey != null && compareUnsigned(previousKey, key) >= 0) {
                        throw new IllegalArgumentException("archive node keys are duplicate or unordered");
                    }
                    previousKey = key;
                    bytes = Math.addExact(bytes, keyLength + valueLength);
                    if (bytes > settings.archiveMaxBytes()) {
                        throw new IllegalArgumentException("archive uncompressed bytes exceed limit");
                    }
                    if (compareOnline) {
                        byte[] online = ledger.snapshotNode(prefixed(storage.prefix(), key));
                        if (!Arrays.equals(online, value)) {
                            throw new IllegalArgumentException("snapshot archive differs from online nodes");
                        }
                        if (expectedReachable != null && !expectedReachable.contains(key)) {
                            throw new IllegalArgumentException(
                                    "MPF archive contains a node outside the sealed-root reachable set");
                        }
                    }
                    consumer.accept(key, value);
                }
                digested.on(false);
                actualDigest = in.readNBytes(32);
                if (actualDigest.length != 32 || in.read() != -1) {
                    throw new IllegalArgumentException("invalid archive digest/trailing bytes");
                }
            }
            if (!MessageDigest.isEqual(actualDigest, digest.digest())) {
                throw new IllegalArgumentException("snapshot archive digest mismatch");
            }
            if (compareOnline) {
                AppLedgerStore.SnapshotNodeStats online;
                if (expectedReachable != null) {
                    online = new AppLedgerStore.SnapshotNodeStats(
                            expectedReachable.nodeCount(), expectedReachable.bytes());
                } else {
                    online = ledger.forEachSnapshotNode(storage.prefix(), settings.archiveMaxNodes(),
                            settings.archiveMaxBytes(), (ignoredKey, ignoredValue) -> { });
                }
                if (online.nodeCount() != count || online.bytes() != bytes) {
                    throw new IllegalArgumentException("snapshot archive omits or adds online records");
                }
            }
            return new ArchiveEnvelope(descriptorBytes, count, bytes);
        } catch (IOException failure) {
            throw new RuntimeException("Failed to verify authenticated snapshot archive", failure);
        }
    }

    private void verifyOnline(SnapshotDescriptorV1 descriptor, StorageKey storage) {
        if (StateCommitmentProfiles.CLASSIC_JMT.id().equals(descriptor.snapshotProfile())) {
            SnapshotJmtStore store = snapshotJmtStore(storage);
            JellyfishMerkleTree tree = new JellyfishMerkleTree(
                    store, JmtProfile.classicBlake2b256V1());
            long version = store.latestVersion();
            byte[] root = store.rootHash(version).orElseThrow();
            byte[] identity = tree.get(IDENTITY_KEY, version).orElse(null);
            if (!Arrays.equals(root, descriptor.snapshotRoot())
                    || !Arrays.equals(identity, identityLeaf(descriptor))) {
                throw new IllegalArgumentException("JMT snapshot root/identity mismatch");
            }
            int maximum = Math.toIntExact(Math.min(Integer.MAX_VALUE - 1L,
                    Math.max(1024L, Math.multiplyExact(descriptor.entryCount() + 1, 5))));
            var report = new com.bloxbean.cardano.vds.jmt.integrity.JmtIntegrityChecker(
                    store, JmtProfile.classicBlake2b256V1()).check(
                    com.bloxbean.cardano.vds.jmt.integrity.JmtIntegrityMode.FULL,
                    com.bloxbean.cardano.vds.jmt.integrity.JmtIntegrityChecker.Options.builder()
                            .maxRecords(maximum).allVersions(false).build());
            if (!report.healthy() || report.truncated()
                    || report.valuesChecked() != descriptor.entryCount() + 1) {
                throw new IllegalArgumentException("JMT snapshot is incomplete or corrupt");
            }
            return;
        }
        SnapshotOverlayNodeStore store = new SnapshotOverlayNodeStore(storage.prefix());
        MpfTrie trie = new MpfTrie(store, descriptor.snapshotRoot());
        SnapshotMpfIntegrity.Result integrity = verifyMpfIntegrity(descriptor, storage);
        byte[] identity = trie.get(IDENTITY_KEY);
        Optional<byte[]> proof = trie.getProofWire(IDENTITY_KEY);
        if (integrity.entryCount() != descriptor.entryCount() + 1
                || !Arrays.equals(identity, identityLeaf(descriptor)) || proof.isEmpty()
                || !trie.verifyProofWire(descriptor.snapshotRoot(), IDENTITY_KEY, identity,
                true, proof.orElseThrow())) {
            throw new IllegalArgumentException("MPF snapshot online root/identity mismatch");
        }
    }

    private void pruneMpfSnapshotIfEnabled(SnapshotDescriptorV1 descriptor, StorageKey storage) {
        if (!settings.mpfPruningEnabled()
                || !StateCommitmentProfiles.MPF.id().equals(descriptor.snapshotProfile())) return;
        SnapshotMpfIntegrity.Result integrity = verifyMpfIntegrity(descriptor, storage);
        SnapshotOverlayNodeStore store = new SnapshotOverlayNodeStore(storage.prefix());
        MpfTrie trie = new MpfTrie(store, descriptor.snapshotRoot());
        byte[] identity = trie.get(IDENTITY_KEY);
        Optional<byte[]> proof = trie.getProofWire(IDENTITY_KEY);
        if (integrity.entryCount() != descriptor.entryCount() + 1
                || !Arrays.equals(identity, identityLeaf(descriptor)) || proof.isEmpty()
                || !trie.verifyProofWire(descriptor.snapshotRoot(), IDENTITY_KEY, identity,
                true, proof.orElseThrow()) || integrity.reachable().isEmpty()) {
            throw new IllegalArgumentException("MPF snapshot cannot be safely reachability-pruned");
        }
        ledger.deleteSnapshotNodesExcept(storage.prefix(), key ->
                        integrity.contains(key),
                settings.archiveMaxNodes(), settings.archiveMaxBytes());
        verifyOnline(descriptor, storage);
    }

    private SnapshotMpfIntegrity.Result verifyMpfIntegrity(
            SnapshotDescriptorV1 descriptor, StorageKey storage) {
        SnapshotMpfIntegrity verifier = new SnapshotMpfIntegrity(
                key -> ledger.snapshotNode(prefixed(storage.prefix(), key)),
                settings.archiveMaxNodes(), settings.archiveMaxBytes());
        SnapshotMpfIntegrity.Result reachable = verifier.verify(descriptor.snapshotRoot());
        return reachable;
    }

    private long physicalArchiveNodeLimit() {
        return Math.multiplyExact(settings.archiveMaxNodes(), 64L);
    }

    private long physicalArchiveByteLimit() {
        return Math.multiplyExact(settings.archiveMaxBytes(), 64L);
    }

    private byte[] identityLeaf(SnapshotDescriptorV1 descriptor) {
        return identityLeaf(descriptor.chainGenerationId(), descriptor.applicationProfileDigest(),
                descriptor.seriesId(), descriptor.sequence(), descriptor.schemaId(),
                descriptor.snapshotProfile(), descriptor.snapshotFormatFingerprint(),
                descriptor.snapshotProofWireVersion(), descriptor.sourceDatasetRoot());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void forceFile(Path path) {
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException failure) {
            throw new RuntimeException("Failed to fsync snapshot archive", failure);
        }
    }

    private static void forceDirectory(Path path) {
        try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException failure) {
            throw new RuntimeException("Failed to fsync snapshot archive directory", failure);
        }
    }

    private static String ascii(byte[] value) { return new String(value, StandardCharsets.US_ASCII); }

    private record ArchiveEnvelope(byte[] descriptorBytes, long nodeCount, long bytes) {
        private ArchiveEnvelope { descriptorBytes = descriptorBytes.clone(); }
        @Override public byte[] descriptorBytes() { return descriptorBytes.clone(); }
    }

    private byte[] identityLeaf(AuthenticatedSnapshotPlanCollector.Begin begin) {
        return identityLeaf(primaryIdentity.genesisId(), primaryIdentity.digest(),
                begin.descriptor().seriesId(), begin.sequence(), begin.descriptor().schemaId(),
                begin.descriptor().snapshotProfile(), begin.descriptor().formatFingerprint(),
                begin.descriptor().proofWireVersion(), begin.sourceDatasetRoot());
    }

    private static byte[] identityLeaf(byte[] chainGeneration, byte[] applicationProfile,
                                       String series, long sequence, String schema,
                                       String snapshotProfile, byte[] formatFingerprint,
                                       String proofWire, byte[] sourceRoot) {
        byte[] seriesBytes = series.getBytes(StandardCharsets.US_ASCII);
        byte[] schemaBytes = schema.getBytes(StandardCharsets.US_ASCII);
        byte[] profileBytes = snapshotProfile.getBytes(StandardCharsets.US_ASCII);
        byte[] wireBytes = proofWire.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer canonical = ByteBuffer.allocate(IDENTITY_DOMAIN.length + 32 + 32
                        + 2 + seriesBytes.length + Long.BYTES + 2 + schemaBytes.length
                        + 2 + profileBytes.length + 32 + 2 + wireBytes.length + 32)
                .put(IDENTITY_DOMAIN).put(chainGeneration).put(applicationProfile)
                .putShort((short) seriesBytes.length).put(seriesBytes).putLong(sequence)
                .putShort((short) schemaBytes.length).put(schemaBytes)
                .putShort((short) profileBytes.length).put(profileBytes).put(formatFingerprint)
                .putShort((short) wireBytes.length).put(wireBytes).put(sourceRoot);
        return Blake2bUtil.blake2bHash256(canonical.array());
    }

    private static void requireMatching(SnapshotBuildReceiptV1 receipt, long sequence,
                                        byte[] descriptorDraftDigest) {
        if (receipt.sequence() != sequence) {
            throw new IllegalArgumentException("snapshot sequence mismatch");
        }
        if (!Arrays.equals(receipt.descriptorDraftDigest(), descriptorDraftDigest)) {
            throw new IllegalArgumentException("snapshot descriptor draft digest mismatch");
        }
    }

    static byte[] descriptorKey(String series, long sequence) {
        return ("snapshots/v1/" + series + "/" + String.format(java.util.Locale.ROOT,
                "%020d", sequence)).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] headKey(String series) {
        return ("snapshots/v1/" + series + "/latest").getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] receiptKey(String series) {
        return ("snapshots/v1/" + series + "/build").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] normalizedRoot(byte[] root) { return root != null ? root : EMPTY_ROOT.clone(); }

    private static byte[] prefixed(byte[] prefix, byte[] key) {
        return ByteBuffer.allocate(prefix.length + key.length).put(prefix).put(key).array();
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int result = Integer.compare(Byte.toUnsignedInt(left[i]), Byte.toUnsignedInt(right[i]));
            if (result != 0) return result;
        }
        return Integer.compare(left.length, right.length);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private record StorageKey(byte[] prefix) {
        private StorageKey { prefix = prefix.clone(); }
        @Override public byte[] prefix() { return prefix.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof StorageKey that && Arrays.equals(prefix, that.prefix);
        }
        @Override public int hashCode() { return Arrays.hashCode(prefix); }
    }

    private static final class ByteKey {
        private final byte[] bytes;
        private final int hash;
        private ByteKey(byte[] bytes) { this.bytes = bytes.clone(); this.hash = Arrays.hashCode(this.bytes); }
        @Override public boolean equals(Object other) {
            return other instanceof ByteKey that && Arrays.equals(bytes, that.bytes);
        }
        @Override public int hashCode() { return hash; }
    }
}
