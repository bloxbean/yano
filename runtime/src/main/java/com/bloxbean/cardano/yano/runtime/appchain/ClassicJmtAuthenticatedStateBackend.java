package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.integrity.JmtIntegrityChecker;
import com.bloxbean.cardano.vds.jmt.integrity.JmtIntegrityMode;
import com.bloxbean.cardano.vds.jmt.store.JmtAccessLease;
import com.bloxbean.cardano.vds.jmt.store.JmtStore;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.state.AuthenticatedStateBackend;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentValues;
import com.bloxbean.cardano.yano.api.appchain.state.StateIntegrityReport;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.rocksdb.WriteBatch;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Prepared classic radix-16 Blake2b-256 CCL JMT backend (ADR-025 Phase 3). */
final class ClassicJmtAuthenticatedStateBackend implements AuthenticatedStateBackend {
    private static final byte[] EMPTY_ROOT = new byte[32];

    private final AppLedgerStore ledger;
    private final SharedRocksDbJmtStore store;
    private final JellyfishMerkleTree tree;
    private final StateCommitmentIdentity identity;
    private final StateCommitFaultInjector faults;

    ClassicJmtAuthenticatedStateBackend(
            AppLedgerStore ledger,
            SharedRocksDbJmtStore store,
            StateCommitmentIdentity identity,
            StateCommitFaultInjector faults
    ) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.store = Objects.requireNonNull(store, "store");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.faults = Objects.requireNonNull(faults, "faults");
        if (identity.legacy()
                || !StateCommitmentProfiles.CLASSIC_JMT.equals(identity.profile())) {
            throw new IllegalArgumentException(
                    "classic JMT backend requires an explicit jmt-blake2b256-v1 identity");
        }
        this.tree = new JellyfishMerkleTree(store, JmtProfile.classicBlake2b256V1());
        verifyVersionHead(ledger.tipHeight(), committedRoot());
    }

    @Override
    public StateCommitmentIdentity identity() {
        return identity;
    }

    @Override
    public CandidateState beginCandidate(long baseHeight, byte[] baseRoot, long targetHeight) {
        byte[] expectedRoot = committedRoot();
        if (baseHeight != ledger.tipHeight() || !Arrays.equals(expectedRoot, baseRoot)) {
            throw new IllegalArgumentException(
                    "candidate base differs from the finalized classic JMT head");
        }
        if (targetHeight != Math.addExact(baseHeight, 1)) {
            throw new IllegalArgumentException(
                    "candidate target height must immediately follow its base");
        }
        verifyVersionHead(baseHeight, expectedRoot);
        JmtAccessLease candidateLease = store.accessCoordinator().tryAcquireUpdate(
                "candidate", targetHeight);
        try {
            JmtCandidate candidate = new JmtCandidate(
                    baseHeight, expectedRoot, targetHeight, candidateLease);
            faults.at(StateCommitFaultInjector.FaultPoint.AFTER_CANDIDATE_OPEN);
            return candidate;
        } catch (Throwable failure) {
            throw closeAfterFailure(failure, candidateLease,
                    "classic JMT candidate open failed");
        }
    }

    @Override
    public Optional<byte[]> get(long height, byte[] canonicalKey) {
        byte[] key = requireKey(canonicalKey);
        try (JmtAccessLease ignored = store.accessCoordinator()
                .tryAcquireRead("logical-get", height)) {
            if (height <= 0 || snapshot(height).isEmpty()) {
                return Optional.empty();
            }
            return logicalValue(tree.get(key, height));
        }
    }

    @Override
    public Optional<StateSnapshot> snapshot(long height) {
        if (height < 0) {
            return Optional.empty();
        }
        if (height == 0) {
            return Optional.of(new StateSnapshot(identity, 0, EMPTY_ROOT));
        }
        Optional<byte[]> root = store.rootHash(height);
        Optional<AppBlock> block = ledger.block(height);
        if (root.isEmpty() || block.isEmpty()
                || !Arrays.equals(root.orElseThrow(), block.orElseThrow().stateRoot())) {
            return Optional.empty();
        }
        return Optional.of(new StateSnapshot(identity, height, root.orElseThrow()));
    }

    @Override
    public Optional<StateProof> prove(long height, byte[] canonicalKey) {
        byte[] key = requireKey(canonicalKey);
        try (JmtAccessLease ignored = store.accessCoordinator()
                .tryAcquireRead("state-proof", height)) {
            Optional<StateSnapshot> snapshot = snapshot(height);
            if (height <= 0 || snapshot.isEmpty()) {
                return Optional.empty();
            }
            return tree.getProof(key, height).flatMap(proof ->
                    tree.getProofWire(key, height).map(wire -> {
                        byte[] committedValue = proof.value();
                        StateProof.Presence presence;
                        if (committedValue == null) {
                            presence = StateProof.Presence.ABSENT;
                        } else if (StateCommitmentValues.isClassicJmtTombstone(committedValue)) {
                            presence = StateProof.Presence.TOMBSTONED;
                        } else {
                            presence = StateProof.Presence.PRESENT;
                        }
                        return new StateProof(
                                snapshot.orElseThrow(), key, committedValue, presence,
                                identity.profile().proofEncodingId(), wire);
                    }));
        }
    }

    @Override
    public StateIntegrityReport verifyIntegrity() {
        long height = ledger.tipHeight();
        byte[] root = committedRoot();
        if (height == 0) {
            boolean valid = ledger.verifyIntegrity() && store.latestRoot().isEmpty();
            return new StateIntegrityReport(identity, 0, root, valid,
                    valid ? "empty ledger and classic JMT store agree"
                            : "empty ledger has a published JMT root");
        }
        boolean headMatches = store.latestRoot()
                .filter(latest -> latest.version() == height)
                .filter(latest -> Arrays.equals(latest.rootHash(), root))
                .isPresent();
        com.bloxbean.cardano.vds.jmt.integrity.JmtIntegrityReport nativeReport =
                new JmtIntegrityChecker(store, JmtProfile.classicBlake2b256V1())
                        .check(JmtIntegrityMode.FULL);
        boolean valid = ledger.verifyIntegrity() && headMatches && nativeReport.healthy();
        String detail = valid
                ? "tip block, JMT version/root, format, nodes, values, and stale indexes agree"
                : "classic JMT integrity failed: head=" + headMatches
                + ", nativeIssues=" + nativeReport.issues().size()
                + ", truncated=" + nativeReport.truncated();
        return new StateIntegrityReport(identity, height, root, valid, detail);
    }

    @Override
    public long oldestProvableHeight() {
        if (ledger.tipHeight() == 0) {
            return 0;
        }
        return Math.max(1, store.pruneWatermark());
    }

    @Override
    public int pruneBefore(long retainFromHeight) {
        if (retainFromHeight <= 0 || retainFromHeight > ledger.tipHeight()) {
            throw new IllegalArgumentException(
                    "JMT retain-from height must be within the finalized chain");
        }
        long currentWatermark = store.pruneWatermark();
        if (currentWatermark >= 0 && retainFromHeight < currentWatermark) {
            throw new IllegalArgumentException(
                    "JMT retain-from height cannot move behind the prune watermark "
                            + currentWatermark);
        }
        return store.pruneUpTo(retainFromHeight);
    }

    private void verifyVersionHead(long height, byte[] root) {
        Optional<JmtStore.VersionedRoot> latest = store.latestRoot();
        if (height == 0) {
            if (latest.isPresent()) {
                throw new IllegalStateException("empty app ledger has a published JMT version");
            }
            return;
        }
        if (latest.isEmpty() || latest.get().version() != height
                || !Arrays.equals(latest.get().rootHash(), root)) {
            throw new IllegalStateException(
                    "app ledger tip and classic JMT version/root differ");
        }
    }

    private byte[] committedRoot() {
        byte[] root = ledger.stateRoot();
        return root != null ? root : EMPTY_ROOT.clone();
    }

    private final class JmtCandidate implements CandidateState {
        private final long baseHeight;
        private final byte[] baseRoot;
        private final long targetHeight;
        private final JmtAccessLease candidateLease;
        private final LinkedHashMap<ByteKey, byte[]> mutations = new LinkedHashMap<>();
        private SharedRocksDbJmtStore.PreparedUpdate calculated;
        private boolean closed;

        private JmtCandidate(
                long baseHeight,
                byte[] baseRoot,
                long targetHeight,
                JmtAccessLease candidateLease
        ) {
            this.baseHeight = baseHeight;
            this.baseRoot = baseRoot.clone();
            this.targetHeight = targetHeight;
            this.candidateLease = candidateLease;
        }

        @Override public long baseHeight() { return baseHeight; }
        @Override public byte[] baseRoot() { return baseRoot.clone(); }
        @Override public long targetHeight() { return targetHeight; }

        @Override
        public Optional<byte[]> get(byte[] key) {
            ensureOpen();
            byte[] canonicalKey = requireKey(key);
            byte[] pending = mutations.get(new ByteKey(canonicalKey));
            if (pending != null) {
                return StateCommitmentValues.isClassicJmtTombstone(pending)
                        ? Optional.empty() : Optional.of(pending.clone());
            }
            if (baseHeight == 0) {
                return Optional.empty();
            }
            return logicalValue(tree.get(canonicalKey, baseHeight));
        }

        @Override
        public void put(byte[] key, byte[] value) {
            ensureOpen();
            byte[] canonicalValue = Objects.requireNonNull(value, "value").clone();
            if (StateCommitmentValues.isClassicJmtTombstone(canonicalValue)) {
                throw new IllegalArgumentException(
                        "value is reserved for classic JMT logical deletion");
            }
            mutations.put(new ByteKey(requireKey(key)), canonicalValue);
            calculated = null;
        }

        @Override
        public void delete(byte[] key) {
            ensureOpen();
            mutations.put(new ByteKey(requireKey(key)),
                    StateCommitmentValues.classicJmtTombstone());
            calculated = null;
        }

        @Override
        public byte[] stateRoot() {
            ensureOpen();
            return calculate().rootHash();
        }

        @Override
        public StagedStateCommit prepare() {
            ensureOpen();
            faults.at(StateCommitFaultInjector.FaultPoint.BEFORE_PREPARE);
            SharedRocksDbJmtStore.PreparedUpdate update = calculate();
            int preparedMutationCount = mutations.size();
            closed = true;
            mutations.clear();
            StagedStateCommit prepared = new JmtPreparedCommit(
                    baseHeight, baseRoot, targetHeight, update, preparedMutationCount,
                    candidateLease);
            try {
                faults.at(StateCommitFaultInjector.FaultPoint.AFTER_PREPARE);
                return prepared;
            } catch (Throwable failure) {
                throw closeAfterFailure(failure, prepared,
                        "classic JMT candidate preparation failed");
            }
        }

        @Override
        public void discard() {
            if (!closed) {
                closed = true;
                mutations.clear();
                calculated = null;
                candidateLease.close();
            }
        }

        @Override public boolean closed() { return closed; }

        private SharedRocksDbJmtStore.PreparedUpdate calculate() {
            if (calculated == null) {
                Map<byte[], byte[]> updates = new LinkedHashMap<>();
                mutations.forEach((key, value) -> updates.put(key.bytes(), value.clone()));
                calculated = store.calculate(tree, targetHeight, updates);
            }
            return calculated;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("classic JMT candidate is closed");
            }
        }
    }

    private final class JmtPreparedCommit implements StagedStateCommit {
        private final long baseHeight;
        private final byte[] baseRoot;
        private final long targetHeight;
        private final SharedRocksDbJmtStore.PreparedUpdate update;
        private final int mutationCount;
        private final JmtAccessLease candidateLease;
        private JmtAccessLease stageLease;
        private boolean staged;
        private boolean closed;

        private JmtPreparedCommit(
                long baseHeight,
                byte[] baseRoot,
                long targetHeight,
                SharedRocksDbJmtStore.PreparedUpdate update,
                int mutationCount,
                JmtAccessLease candidateLease
        ) {
            this.baseHeight = baseHeight;
            this.baseRoot = baseRoot.clone();
            this.targetHeight = targetHeight;
            this.update = update;
            this.mutationCount = mutationCount;
            this.candidateLease = candidateLease;
        }

        @Override public StateCommitmentIdentity identity() { return identity; }
        @Override public long baseHeight() { return baseHeight; }
        @Override public byte[] baseRoot() { return baseRoot.clone(); }
        @Override public long targetHeight() { return targetHeight; }
        @Override public byte[] stateRoot() { return update.rootHash(); }
        @Override public int mutationCount() { return mutationCount; }
        @Override public boolean staged() { return staged; }

        @Override
        public void stage(WriteBatch batch) {
            Objects.requireNonNull(batch, "batch");
            if (closed || staged) {
                throw new IllegalStateException(
                        "prepared classic JMT commit is already consumed");
            }
            faults.at(StateCommitFaultInjector.FaultPoint.BEFORE_BACKEND_STAGE);
            stageLease = store.accessCoordinator().tryAcquireUpdate(
                    "stage-prepared", targetHeight);
            try {
                store.stage(update, batch);
                staged = true;
                faults.at(StateCommitFaultInjector.FaultPoint.AFTER_BACKEND_STAGE);
            } catch (Throwable failure) {
                JmtAccessLease failedLease = stageLease;
                stageLease = null;
                throw closeAfterFailure(failure, failedLease,
                        "classic JMT staging failed");
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Throwable failure = null;
                if (stageLease != null) {
                    try {
                        stageLease.close();
                    } catch (Throwable closeFailure) {
                        failure = closeFailure;
                    }
                    stageLease = null;
                }
                try {
                    candidateLease.close();
                } catch (Throwable closeFailure) {
                    failure = LifecycleFailures.merge(failure, closeFailure);
                }
                if (failure != null) {
                    throw asRuntime(failure, "classic JMT prepared commit close failed");
                }
            }
        }
    }

    private static RuntimeException closeAfterFailure(
            Throwable failure,
            AutoCloseable resource,
            String context
    ) {
        Throwable outcome = failure;
        try {
            resource.close();
        } catch (Throwable closeFailure) {
            outcome = LifecycleFailures.merge(outcome, closeFailure);
        }
        return asRuntime(outcome, context);
    }

    private static RuntimeException asRuntime(Throwable failure, String context) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(context, failure);
    }

    private static Optional<byte[]> logicalValue(Optional<byte[]> committed) {
        return committed.filter(value ->
                !StateCommitmentValues.isClassicJmtTombstone(value));
    }

    private static byte[] requireKey(byte[] key) {
        byte[] copy = Objects.requireNonNull(key, "key").clone();
        if (copy.length == 0) {
            throw new IllegalArgumentException(
                    "authenticated-state key must not be empty");
        }
        return copy;
    }

    private static final class ByteKey {
        private final byte[] bytes;
        private final int hashCode;

        private ByteKey(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
            this.hashCode = Arrays.hashCode(this.bytes);
        }

        private byte[] bytes() { return bytes.clone(); }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteKey key && Arrays.equals(bytes, key.bytes);
        }

        @Override public int hashCode() { return hashCode; }
    }
}
