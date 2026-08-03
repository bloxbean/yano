package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.NodeKey;
import com.bloxbean.cardano.vds.core.NibblePath;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentValues;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassicJmtPreparedRuntimeTest {
    private static final byte[] KEY_A = bytes("classic-key-a");
    private static final byte[] KEY_B = bytes("classic-key-b");
    private static final byte[] MISSING = bytes("classic-missing");
    private static final byte[] ONE = bytes("one");
    private static final byte[] TWO = bytes("two");
    private static final StateCommitmentIdentity IDENTITY =
            StateCommitmentIdentity.explicit(
                    StateCommitmentProfiles.CLASSIC_JMT, repeated(0x25, 32));

    @Test
    void preparedRuntimeMatchesPinnedCclRootAndProofBytes(@TempDir Path directory) {
        Map<byte[], byte[]> updates = new LinkedHashMap<>();
        updates.put(KEY_A, ONE);
        updates.put(KEY_B, TWO);
        byte[] expectedRoot;
        byte[] expectedProof;
        try (InMemoryJmtStore baselineStore = new InMemoryJmtStore()) {
            JellyfishMerkleTree baseline = new JellyfishMerkleTree(
                    baselineStore, JmtProfile.classicBlake2b256V1());
            expectedRoot = baseline.put(1, updates).rootHash();
            expectedProof = baseline.getProofWire(KEY_A, 1).orElseThrow();
        }

        Path ledgerPath = directory.resolve("ledger");
        try (AppLedgerStore ledger = open(ledgerPath)) {
            CandidateState candidate = ledger.stateBackend()
                    .beginCandidate(0, new byte[32], 1);
            candidate.put(KEY_A, ONE);
            candidate.put(KEY_B, TWO);
            assertThat(candidate.stateRoot()).isEqualTo(expectedRoot);
            assertThat(ledger.stateRoot()).isNull();
            assertThat(ledger.stateGet(KEY_A)).isEmpty();

            try (StagedStateCommit prepared = (StagedStateCommit) candidate.prepare()) {
                assertThat(prepared.stateRoot()).isEqualTo(expectedRoot);
                commit(ledger, 1, AppBlock.GENESIS_PREV_HASH, prepared);
            }

            assertThat(ledger.stateGet(KEY_A)).hasValue(ONE);
            assertThat(ledger.stateProofWire(KEY_A)).hasValue(expectedProof);
            assertThat(ledger.stateBackend().verifyIntegrity().valid()).isTrue();
        }

        try (AppLedgerStore reopened = open(ledgerPath)) {
            assertThat(reopened.tipHeight()).isEqualTo(1);
            assertThat(reopened.stateRoot()).isEqualTo(expectedRoot);
            assertThat(reopened.stateProofWire(KEY_A)).hasValue(expectedProof);
            assertThat(reopened.stateBackend().identity().profile())
                    .isEqualTo(StateCommitmentProfiles.CLASSIC_JMT);
        }
    }

    @Test
    void retainedVersionsClassifyPresentAbsentAndLogicalTombstoneAndPruneSafely(
            @TempDir Path directory
    ) {
        Path ledgerPath = directory.resolve("ledger");
        byte[] hash1;
        byte[] hash2;
        try (AppLedgerStore ledger = open(ledgerPath)) {
            try (StagedStateCommit first = prepare(ledger, candidate -> {
                candidate.put(KEY_A, ONE);
                candidate.put(KEY_B, TWO);
            })) {
                AppBlock block = commit(ledger, 1, AppBlock.GENESIS_PREV_HASH, first);
                hash1 = AppBlockCodec.blockHash(block);
            }
            try (StagedStateCommit second = prepare(ledger, candidate -> {
                candidate.put(KEY_A, TWO);
                candidate.delete(KEY_B);
                assertThat(candidate.get(KEY_B)).isEmpty();
            })) {
                AppBlock block = commit(ledger, 2, hash1, second);
                hash2 = AppBlockCodec.blockHash(block);
            }
            try (StagedStateCommit third = prepare(ledger, candidate -> { })) {
                commit(ledger, 3, hash2, third);
            }

            StateProof oldValue = ledger.stateBackend().prove(1, KEY_B).orElseThrow();
            StateProof tombstone = ledger.stateBackend().prove(2, KEY_B).orElseThrow();
            StateProof absent = ledger.stateBackend().prove(2, MISSING).orElseThrow();
            assertThat(oldValue.presence()).isEqualTo(StateProof.Presence.PRESENT);
            assertThat(oldValue.value()).isEqualTo(TWO);
            assertThat(tombstone.presence()).isEqualTo(StateProof.Presence.TOMBSTONED);
            assertThat(tombstone.value())
                    .isEqualTo(StateCommitmentValues.classicJmtTombstone());
            assertThat(ledger.stateProofSnapshotAtHeight(2, KEY_B))
                    .get().extracting(snapshot -> snapshot.value())
                    .isEqualTo(StateCommitmentValues.classicJmtTombstone());
            assertThat(absent.presence()).isEqualTo(StateProof.Presence.ABSENT);
            assertThat(absent.value()).isNull();
            assertThat(ledger.stateBackend().get(1, KEY_B)).hasValue(TWO);
            assertThat(ledger.stateBackend().get(2, KEY_B)).isEmpty();

            try (InMemoryJmtStore verifierStore = new InMemoryJmtStore()) {
                JellyfishMerkleTree verifier = new JellyfishMerkleTree(
                        verifierStore, JmtProfile.classicBlake2b256V1());
                assertThat(verifier.verifyProofWire(
                        tombstone.snapshot().stateRoot(), KEY_B, tombstone.value(), true,
                        tombstone.nativeProof())).isTrue();
                assertThat(verifier.verifyProofWire(
                        absent.snapshot().stateRoot(), MISSING, null, false,
                        absent.nativeProof())).isTrue();
            }

            CandidateState activeCandidate = ledger.stateBackend().beginCandidate(
                    3, ledger.stateRoot(), 4);
            assertThatThrownBy(() -> ledger.pruneStateProofsBefore(2))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("candidate");
            activeCandidate.discard();

            assertThat(ledger.stateBackend().oldestProvableHeight()).isEqualTo(1);
            assertThat(ledger.pruneStateProofsBefore(2)).isPositive();
            assertThat(ledger.stateBackend().oldestProvableHeight()).isEqualTo(2);
            assertThat(ledger.stateBackend().prove(1, KEY_A)).isEmpty();
            assertThat(ledger.stateBackend().prove(2, KEY_A)).isPresent();
            assertThat(ledger.stateBackend().prove(3, KEY_A)).isPresent();
            assertThatThrownBy(() -> ledger.pruneStateProofsBefore(1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot move behind");
            assertThat(ledger.stateBackend().oldestProvableHeight()).isEqualTo(2);
            assertThat(ledger.stateBackend().verifyIntegrity().valid()).isTrue();

            Path snapshot = directory.resolve("snapshot");
            ledger.createSnapshot(snapshot.toString());
            try (AppLedgerStore restored = open(snapshot)) {
                assertThat(restored.tipHeight()).isEqualTo(3);
                assertThat(restored.stateBackend().oldestProvableHeight()).isEqualTo(2);
                assertThat(restored.stateBackend().prove(1, KEY_A)).isEmpty();
                assertThat(restored.stateBackend().prove(2, KEY_B))
                        .get().extracting(StateProof::presence)
                        .isEqualTo(StateProof.Presence.TOMBSTONED);
                assertThat(restored.stateBackend().verifyIntegrity().valid()).isTrue();
            }
        }
    }

    @Test
    void everyPreparedCommitFaultRestartsAtOldOrNewClassicJmtState(
            @TempDir Path directory
    ) {
        for (StateCommitFaultInjector.FaultPoint point
                : StateCommitFaultInjector.FaultPoint.values()) {
            if (point == StateCommitFaultInjector.FaultPoint.BEFORE_RESTART_VERIFICATION
                    || point == StateCommitFaultInjector.FaultPoint.AFTER_RESTART_VERIFICATION) {
                continue;
            }
            Path ledgerPath = directory.resolve(point.name().toLowerCase());
            OneShotFault fault = new OneShotFault(point);
            try (AppLedgerStore ledger = new AppLedgerStore(
                    ledgerPath.toString(), LoggerFactory.getLogger("classic-jmt-fault"),
                    IDENTITY, fault)) {
                assertThatThrownBy(() -> {
                    CandidateState candidate = ledger.stateBackend()
                            .beginCandidate(0, new byte[32], 1);
                    StagedStateCommit prepared = null;
                    try {
                        candidate.put(KEY_A, ONE);
                        prepared = (StagedStateCommit) candidate.prepare();
                        commit(ledger, 1, AppBlock.GENESIS_PREV_HASH, prepared);
                    } finally {
                        if (prepared != null) {
                            prepared.close();
                        } else {
                            candidate.discard();
                        }
                    }
                }).isInstanceOf(InjectedFault.class)
                        .hasMessageContaining(point.name());
            }

            boolean committed = point == StateCommitFaultInjector.FaultPoint.AFTER_DURABLE_WRITE
                    || point == StateCommitFaultInjector.FaultPoint.AFTER_COMMIT_VERIFICATION;
            try (AppLedgerStore reopened = open(ledgerPath)) {
                assertThat(reopened.tipHeight()).isEqualTo(committed ? 1 : 0);
                assertThat(reopened.stateGet(KEY_A).isPresent()).isEqualTo(committed);
                assertThat(reopened.stateBackend().verifyIntegrity().valid()).isTrue();
            }
        }
    }

    @Test
    void independentMembersAndRestartedCatchupProduceIdenticalVersionRoots(
            @TempDir Path directory
    ) {
        Path leftPath = directory.resolve("left");
        Path rightPath = directory.resolve("right");
        byte[] previousLeft = AppBlock.GENESIS_PREV_HASH;
        byte[] previousRight = AppBlock.GENESIS_PREV_HASH;
        for (int height = 1; height <= 20; height++) {
            try (AppLedgerStore left = open(leftPath);
                 AppLedgerStore right = open(rightPath)) {
                int current = height;
                try (StagedStateCommit leftCommit = prepare(left, candidate ->
                        candidate.put(bytes("key-" + current), bytes("value-" + current)));
                     StagedStateCommit rightCommit = prepare(right, candidate ->
                             candidate.put(bytes("key-" + current), bytes("value-" + current)))) {
                    assertThat(leftCommit.stateRoot()).isEqualTo(rightCommit.stateRoot());
                    AppBlock leftBlock = commit(left, height, previousLeft, leftCommit);
                    AppBlock rightBlock = commit(right, height, previousRight, rightCommit);
                    previousLeft = AppBlockCodec.blockHash(leftBlock);
                    previousRight = AppBlockCodec.blockHash(rightBlock);
                    assertThat(previousLeft).isEqualTo(previousRight);
                }
            }
        }
        try (AppLedgerStore left = open(leftPath);
             AppLedgerStore right = open(rightPath)) {
            assertThat(left.tipHeight()).isEqualTo(20);
            assertThat(left.stateRoot()).isEqualTo(right.stateRoot());
            assertThat(left.stateBackend().verifyIntegrity().valid()).isTrue();
            assertThat(right.stateBackend().verifyIntegrity().valid()).isTrue();
        }
    }

    @Test
    void formatAndNodeCorruptionFailClosedAndFailedOpenReleasesRocksDb(
            @TempDir Path directory
    ) throws Exception {
        Path ledgerPath = directory.resolve("ledger");
        try (AppLedgerStore ledger = open(ledgerPath);
             StagedStateCommit prepared = prepare(ledger,
                     candidate -> candidate.put(KEY_A, ONE))) {
            commit(ledger, 1, AppBlock.GENESIS_PREV_HASH, prepared);
        }

        rawMutation(ledgerPath, "jmt_metadata", bytes("JMT_FORMAT"),
                new byte[]{1, 2, 3}, false);
        assertThatThrownBy(() -> open(ledgerPath))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Malformed JMT format descriptor");

        rawMutation(ledgerPath, "jmt_metadata", bytes("JMT_FORMAT"),
                JmtProfile.classicBlake2b256V1().format().encode(), false);
        try (AppLedgerStore repaired = open(ledgerPath)) {
            assertThat(repaired.stateBackend().verifyIntegrity().valid()).isTrue();
        }

        rawMutation(ledgerPath, "jmt_nodes",
                NodeKey.of(NibblePath.EMPTY, 1).toBytes(), null, true);
        try (AppLedgerStore corrupted = open(ledgerPath)) {
            assertThat(corrupted.stateBackend().verifyIntegrity().valid()).isFalse();
        }
    }

    private static StagedStateCommit prepare(
            AppLedgerStore ledger,
            java.util.function.Consumer<CandidateState> mutations
    ) {
        long baseHeight = ledger.tipHeight();
        byte[] root = ledger.stateRoot();
        CandidateState candidate = ledger.stateBackend().beginCandidate(
                baseHeight, root != null ? root : new byte[32], baseHeight + 1);
        mutations.accept(candidate);
        return (StagedStateCommit) candidate.prepare();
    }

    private static AppBlock commit(
            AppLedgerStore ledger,
            long height,
            byte[] previousHash,
            StagedStateCommit prepared
    ) {
        AppBlock block = new AppBlock(
                AppBlock.BLOCK_VERSION,
                "classic-chain",
                height,
                previousHash,
                height,
                new byte[0],
                1_700_000_000_000L + height,
                AppBlockCodec.messagesRoot(List.of()),
                prepared.stateRoot(),
                List.of(),
                new byte[32],
                FinalityCert.empty());
        try (WriteBatch batch = new WriteBatch()) {
            ledger.commitBlock(block, AppBlockCodec.blockHash(block),
                    prepared, batch, List.of());
        }
        return block;
    }

    private static AppLedgerStore open(Path path) {
        return new AppLedgerStore(path.toString(),
                LoggerFactory.getLogger("classic-jmt-test"), IDENTITY);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeated(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static void rawMutation(
            Path path,
            String family,
            byte[] key,
            byte[] value,
            boolean delete
    ) throws Exception {
        RocksDB.loadLibrary();
        List<byte[]> names;
        try (Options options = new Options()) {
            names = RocksDB.listColumnFamilies(options, path.toString());
        }
        List<ColumnFamilyHandle> handles = new java.util.ArrayList<>();
        try (ColumnFamilyOptions familyOptions = new ColumnFamilyOptions();
             DBOptions options = new DBOptions()) {
            RocksDB db = RocksDB.open(options, path.toString(),
                    names.stream().map(name ->
                            new ColumnFamilyDescriptor(name, familyOptions)).toList(),
                    handles);
            try {
                int index = -1;
                for (int candidate = 0; candidate < names.size(); candidate++) {
                    if (Arrays.equals(names.get(candidate), bytes(family))) {
                        index = candidate;
                        break;
                    }
                }
                if (index < 0) {
                    throw new IllegalStateException("missing column family " + family);
                }
                if (delete) {
                    db.delete(handles.get(index), key);
                } else {
                    db.put(handles.get(index), key, value);
                }
            } finally {
                handles.forEach(ColumnFamilyHandle::close);
                db.close();
            }
        }
    }

    private static final class OneShotFault implements StateCommitFaultInjector {
        private final FaultPoint target;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        private OneShotFault(FaultPoint target) {
            this.target = target;
        }

        @Override
        public void at(FaultPoint point) {
            if (point == target && armed.compareAndSet(true, false)) {
                throw new InjectedFault(point.name());
            }
        }
    }

    private static final class InjectedFault extends RuntimeException {
        private InjectedFault(String point) {
            super("injected state commit fault at " + point);
        }
    }
}
