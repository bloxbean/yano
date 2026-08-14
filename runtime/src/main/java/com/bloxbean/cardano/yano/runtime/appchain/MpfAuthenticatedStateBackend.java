package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.rocksdb.RocksDbNodeStore;
import com.bloxbean.cardano.vds.mpf.rocksdb.gc.GcOptions;
import com.bloxbean.cardano.vds.mpf.rocksdb.gc.GcReport;
import com.bloxbean.cardano.vds.mpf.rocksdb.gc.strategy.OnDiskMarkSweepStrategy;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.state.AuthenticatedStateBackend;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateIntegrityReport;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.StampedLock;

/** CCL MPF adapter with side-effect-free candidates and external-batch preparation. */
final class MpfAuthenticatedStateBackend implements AuthenticatedStateBackend {
    private static final byte[] EMPTY_ROOT = new byte[32];

    private final AppLedgerStore ledger;
    private final RocksDbNodeStore durableNodes;
    private final StateCommitmentIdentity identity;
    private final StateCommitFaultInjector faults;
    /** Candidates/proofs are readers; destructive reachability GC is the sole writer. */
    private final StampedLock access = new StampedLock();

    MpfAuthenticatedStateBackend(
            AppLedgerStore ledger,
            RocksDbNodeStore durableNodes,
            StateCommitmentIdentity identity,
            StateCommitFaultInjector faults
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.durableNodes = Objects.requireNonNull(durableNodes, "durableNodes");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.faults = Objects.requireNonNull(faults, "faults");
        if (!StateCommitmentProfiles.MPF.equals(identity.profile())) {
            throw new IllegalArgumentException("MPF backend requires mpf-blake2b256-v1");
        }
        long watermark = ledger.mpfProofPruneWatermark();
        long completed = ledger.mpfProofGcCompletedWatermark();
        if (watermark < 1 || completed < 0 || completed > watermark
                || (ledger.tipHeight() > 0 && watermark > ledger.tipHeight())) {
            throw new IllegalStateException("Invalid retained MPF proof watermark " + watermark);
        }
    }

    @Override
    public StateCommitmentIdentity identity() {
        return identity;
    }

    @Override
    public CandidateState beginCandidate(long baseHeight, byte[] baseRoot, long targetHeight) {
        long stamp = access.readLock();
        try {
            byte[] expectedRoot = committedRoot();
            if (baseHeight != ledger.tipHeight() || !Arrays.equals(expectedRoot, baseRoot)) {
                throw new IllegalArgumentException(
                        "candidate base differs from the finalized MPF head");
            }
            if (targetHeight != Math.addExact(baseHeight, 1)) {
                throw new IllegalArgumentException(
                        "candidate target height must immediately follow its base");
            }
            MpfCandidate candidate = new MpfCandidate(
                    baseHeight, expectedRoot, targetHeight, stamp);
            faults.at(StateCommitFaultInjector.FaultPoint.AFTER_CANDIDATE_OPEN);
            return candidate;
        } catch (RuntimeException | Error failure) {
            access.unlockRead(stamp);
            throw failure;
        }
    }

    @Override
    public Optional<StateSnapshot> snapshot(long height) {
        if (height < 0) {
            return Optional.empty();
        }
        if (height > 0 && height < oldestProvableHeight()) {
            return Optional.empty();
        }
        if (height == 0) {
            return Optional.of(new StateSnapshot(identity, 0, EMPTY_ROOT));
        }
        return ledger.block(height)
                .map(AppBlock::stateRoot)
                .map(root -> new StateSnapshot(identity, height, root));
    }

    @Override
    public Optional<byte[]> get(long height, byte[] canonicalKey) {
        Objects.requireNonNull(canonicalKey, "canonicalKey");
        long stamp = access.readLock();
        try {
            return snapshot(height).flatMap(snapshot -> {
                if (height == 0) {
                    return Optional.empty();
                }
                return Optional.ofNullable(new MpfTrie(durableNodes, snapshot.stateRoot())
                        .get(canonicalKey));
            });
        } finally {
            access.unlockRead(stamp);
        }
    }

    @Override
    public Optional<StateProof> prove(long height, byte[] canonicalKey) {
        Objects.requireNonNull(canonicalKey, "canonicalKey");
        long stamp = access.readLock();
        try {
            return snapshot(height).flatMap(snapshot -> {
                if (height == 0) {
                    return Optional.empty();
                }
                MpfTrie trie = new MpfTrie(durableNodes, snapshot.stateRoot());
                Optional<byte[]> proof = trie.getProofWire(canonicalKey);
                if (proof.isEmpty()) {
                    return Optional.empty();
                }
                byte[] value = trie.get(canonicalKey);
                return Optional.of(new StateProof(
                        snapshot,
                        canonicalKey,
                        value,
                        value != null ? StateProof.Presence.PRESENT : StateProof.Presence.ABSENT,
                        identity.profile().proofEncodingId(),
                        proof.orElseThrow()));
            });
        } finally {
            access.unlockRead(stamp);
        }
    }

    @Override
    public StateIntegrityReport verifyIntegrity() {
        long stamp = access.readLock();
        try {
            long height = ledger.tipHeight();
            byte[] root = committedRoot();
            boolean rootPresent = Arrays.equals(root, EMPTY_ROOT) || durableNodes.get(root) != null;
            boolean valid = ledger.verifyIntegrity() && rootPresent;
            return new StateIntegrityReport(identity, height, root, valid,
                    valid
                            ? "tip block, committed MPF root, and root node agree"
                            : "tip block, committed MPF root, or root node differ");
        } finally {
            access.unlockRead(stamp);
        }
    }

    @Override
    public long oldestProvableHeight() {
        return ledger.tipHeight() == 0 ? 0 : ledger.mpfProofPruneWatermark();
    }

    @Override
    public int pruneBefore(long retainFromHeight) {
        long stamp = access.writeLock();
        int deleted;
        try {
            long tip = ledger.tipHeight();
            long current = oldestProvableHeight();
            if (retainFromHeight < current) {
                throw new IllegalArgumentException(
                        "MPF proof pruning cannot restore an older proof height");
            }
            if (retainFromHeight < 1 || retainFromHeight > tip) {
                throw new IllegalArgumentException(
                        "MPF proof retain height must be between 1 and the finalized tip");
            }
            if (retainFromHeight == current
                    && ledger.mpfProofGcCompletedWatermark() >= retainFromHeight) {
                return 0;
            }

            List<StateSnapshot> retained = new ArrayList<>();
            for (long height = retainFromHeight; height <= tip; height++) {
                long retainedHeight = height;
                retained.add(ledger.block(retainedHeight)
                        .map(AppBlock::stateRoot)
                        .map(root -> new StateSnapshot(identity, retainedHeight, root))
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing finalized app block while retaining MPF root at height "
                                        + retainedHeight)));
            }

            // Advance the visible boundary first. A process death can under-claim retained
            // roots, but can never advertise a root that a partly completed sweep removed.
            if (retainFromHeight > current) {
                ledger.advanceMpfProofPruneWatermark(retainFromHeight);
            }
            GcOptions options = new GcOptions();
            options.useSnapshot = true;
            options.deleteBatchSize = 10_000;
            GcReport report;
            try {
                report = new OnDiskMarkSweepStrategy().run(
                        durableNodes, null,
                        ignored -> retained.stream().map(StateSnapshot::stateRoot).toList(),
                        options);
            } catch (Exception failure) {
                throw new RuntimeException("MPF reachability pruning failed", failure);
            }
            verifyRetainedRoots(retained);
            deleted = report.deleted > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : Math.toIntExact(report.deleted);
        } finally {
            access.unlockWrite(stamp);
        }
        // RocksDB compaction is concurrency-safe and need not extend the exclusive
        // candidate/proof pause imposed by the reachability mark-and-sweep itself.
        ledger.compactMpfNodes();
        ledger.completeMpfProofGc(retainFromHeight);
        return deleted;
    }

    private void verifyRetainedRoots(List<StateSnapshot> retained) {
        byte[] markerKey = StateCommitmentIdentity.markerKey();
        byte[] markerValue = identity.canonicalBytes();
        for (StateSnapshot snapshot : retained) {
            MpfTrie trie = new MpfTrie(durableNodes, snapshot.stateRoot());
            byte[] value = trie.get(markerKey);
            Optional<byte[]> proof = trie.getProofWire(markerKey);
            if (!Arrays.equals(value, markerValue) || proof.isEmpty()
                    || !trie.verifyProofWire(snapshot.stateRoot(), markerKey, markerValue,
                    true, proof.orElseThrow())) {
                throw new IllegalStateException(
                        "Retained MPF root failed verification at height " + snapshot.height());
            }
        }
    }

    private byte[] committedRoot() {
        byte[] root = ledger.stateRoot();
        return root != null ? root : EMPTY_ROOT.clone();
    }

    private final class MpfCandidate implements CandidateState {
        private final long baseHeight;
        private final byte[] baseRoot;
        private final long targetHeight;
        private final OverlayNodeStore overlay;
        private final MpfTrie trie;
        private long accessStamp;
        private boolean closed;

        private MpfCandidate(long baseHeight, byte[] baseRoot, long targetHeight,
                             long accessStamp) {
            this.baseHeight = baseHeight;
            this.baseRoot = baseRoot.clone();
            this.targetHeight = targetHeight;
            this.accessStamp = accessStamp;
            this.overlay = new OverlayNodeStore(durableNodes);
            this.trie = baseHeight == 0 && Arrays.equals(baseRoot, EMPTY_ROOT)
                    ? new MpfTrie(overlay)
                    : new MpfTrie(overlay, baseRoot);
        }

        @Override
        public long baseHeight() {
            return baseHeight;
        }

        @Override
        public byte[] baseRoot() {
            return baseRoot.clone();
        }

        @Override
        public long targetHeight() {
            return targetHeight;
        }

        @Override
        public Optional<byte[]> get(byte[] key) {
            ensureOpen();
            return Optional.ofNullable(trie.get(requireKey(key)));
        }

        @Override
        public void put(byte[] key, byte[] value) {
            ensureOpen();
            trie.put(requireKey(key), Objects.requireNonNull(value, "value"));
        }

        @Override
        public void delete(byte[] key) {
            ensureOpen();
            trie.delete(requireKey(key));
        }

        @Override
        public byte[] stateRoot() {
            ensureOpen();
            byte[] root = trie.getRootHash();
            return root != null ? root : EMPTY_ROOT.clone();
        }

        @Override
        public StagedStateCommit prepare() {
            ensureOpen();
            faults.at(StateCommitFaultInjector.FaultPoint.BEFORE_PREPARE);
            byte[] root = stateRoot();
            Map<ByteKey, Mutation> mutations = overlay.freeze();
            closed = true;
            StagedStateCommit prepared = new MpfPreparedCommit(
                    baseHeight, baseRoot, targetHeight, root, mutations, takeAccessStamp());
            try {
                faults.at(StateCommitFaultInjector.FaultPoint.AFTER_PREPARE);
                return prepared;
            } catch (RuntimeException failure) {
                prepared.close();
                throw failure;
            }
        }

        @Override
        public void discard() {
            if (!closed) {
                closed = true;
                overlay.clear();
                releaseAccess();
            }
        }

        @Override
        public boolean closed() {
            return closed;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("MPF candidate is closed");
            }
        }

        private long takeAccessStamp() {
            long stamp = accessStamp;
            accessStamp = 0;
            return stamp;
        }

        private void releaseAccess() {
            long stamp = takeAccessStamp();
            if (stamp != 0) {
                access.unlockRead(stamp);
            }
        }
    }

    private final class MpfPreparedCommit implements StagedStateCommit {
        private final long baseHeight;
        private final byte[] baseRoot;
        private final long targetHeight;
        private final byte[] stateRoot;
        private Map<ByteKey, Mutation> mutations;
        private final int mutationCount;
        private long accessStamp;
        private boolean staged;
        private boolean closed;

        private MpfPreparedCommit(long baseHeight, byte[] baseRoot, long targetHeight,
                                  byte[] stateRoot, Map<ByteKey, Mutation> mutations,
                                  long accessStamp) {
            this.baseHeight = baseHeight;
            this.baseRoot = baseRoot.clone();
            this.targetHeight = targetHeight;
            this.stateRoot = stateRoot.clone();
            this.mutations = mutations;
            this.mutationCount = mutations.size();
            this.accessStamp = accessStamp;
        }

        @Override public StateCommitmentIdentity identity() { return identity; }
        @Override public long baseHeight() { return baseHeight; }
        @Override public byte[] baseRoot() { return baseRoot.clone(); }
        @Override public long targetHeight() { return targetHeight; }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
        @Override public int mutationCount() { return mutationCount; }
        @Override public boolean staged() { return staged; }

        @Override
        public void stage(WriteBatch batch) {
            Objects.requireNonNull(batch, "batch");
            if (closed || staged) {
                throw new IllegalStateException("prepared MPF commit is already consumed");
            }
            faults.at(StateCommitFaultInjector.FaultPoint.BEFORE_BACKEND_STAGE);
            try {
                for (Map.Entry<ByteKey, Mutation> entry : mutations.entrySet()) {
                    byte[] durableKey = durableNodes.keyPrefixer().prefix(entry.getKey().bytes());
                    Mutation mutation = entry.getValue();
                    if (mutation.delete()) {
                        batch.delete(durableNodes.nodesHandle(), durableKey);
                    } else {
                        batch.put(durableNodes.nodesHandle(), durableKey, mutation.value());
                    }
                }
            } catch (RocksDBException failure) {
                throw new RuntimeException("Failed to stage prepared MPF nodes", failure);
            }
            staged = true;
            faults.at(StateCommitFaultInjector.FaultPoint.AFTER_BACKEND_STAGE);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (mutations != null) {
                    mutations.clear();
                    mutations = null;
                }
                if (accessStamp != 0) {
                    access.unlockRead(accessStamp);
                    accessStamp = 0;
                }
            }
        }
    }

    private static final class OverlayNodeStore implements NodeStore {
        private final NodeStore durable;
        private final LinkedHashMap<ByteKey, Mutation> mutations = new LinkedHashMap<>();

        private OverlayNodeStore(NodeStore durable) {
            this.durable = durable;
        }

        @Override
        public byte[] get(byte[] key) {
            Mutation mutation = mutations.get(new ByteKey(key));
            if (mutation != null) {
                return mutation.delete() ? null : mutation.value();
            }
            byte[] value = durable.get(key);
            return value != null ? value.clone() : null;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            mutations.put(new ByteKey(key), new Mutation(false, value));
        }

        @Override
        public void delete(byte[] key) {
            mutations.put(new ByteKey(key), new Mutation(true, null));
        }

        private Map<ByteKey, Mutation> freeze() {
            LinkedHashMap<ByteKey, Mutation> frozen = new LinkedHashMap<>();
            mutations.forEach((key, value) -> frozen.put(key.copy(), value.copy()));
            mutations.clear();
            return frozen;
        }

        private void clear() {
            mutations.clear();
        }
    }

    private record Mutation(boolean delete, byte[] value) {
        private Mutation {
            value = value != null ? value.clone() : null;
            if (delete == (value != null)) {
                throw new IllegalArgumentException("node mutation delete/value differ");
            }
        }

        @Override public byte[] value() { return value != null ? value.clone() : null; }
        private Mutation copy() { return new Mutation(delete, value); }
    }

    private static final class ByteKey {
        private final byte[] bytes;
        private final int hashCode;

        private ByteKey(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
            this.hashCode = Arrays.hashCode(this.bytes);
        }

        private byte[] bytes() { return bytes.clone(); }
        private ByteKey copy() { return new ByteKey(bytes); }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteKey key && Arrays.equals(bytes, key.bytes);
        }

        @Override public int hashCode() { return hashCode; }
    }

    private static byte[] requireKey(byte[] key) {
        byte[] copy = Objects.requireNonNull(key, "key").clone();
        if (copy.length == 0) {
            throw new IllegalArgumentException("authenticated-state key must not be empty");
        }
        return copy;
    }
}
