package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreparedStateCommitRuntimeTest {
    private static final byte[] KEY = "legacy-key".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "legacy-value".getBytes(StandardCharsets.UTF_8);

    @Test
    void legacyMpfRootAndProofBytesRemainIdenticalAndCandidateIsSideEffectFree(
            @TempDir Path directory
    ) {
        MapNodeStore baselineNodes = new MapNodeStore();
        MpfTrie baseline = new MpfTrie(baselineNodes);
        baseline.put(KEY, VALUE);
        baseline.put(bytes("second"), bytes("two"));
        baseline.put(bytes("second"), bytes("two-updated"));
        byte[] expectedRoot = baseline.getRootHash();
        byte[] expectedProof = baseline.getProofWire(KEY).orElseThrow();

        Path ledgerPath = directory.resolve("ledger");
        try (AppLedgerStore ledger = new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-test"))) {
            CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            candidate.put(KEY, VALUE);
            candidate.put(bytes("second"), bytes("two"));
            candidate.put(bytes("second"), bytes("two-updated"));

            assertThat(candidate.get(KEY)).hasValue(VALUE);
            assertThat(ledger.stateRoot()).isNull();
            assertThat(ledger.stateGet(KEY)).isEmpty();

            StagedStateCommit prepared = (StagedStateCommit) candidate.prepare();
            assertThat(prepared.stateRoot()).isEqualTo(expectedRoot);
            assertThat(prepared.mutationCount()).isPositive();
            assertThat(ledger.stateRoot()).isNull();

            AppBlock block = block(1, AppBlock.GENESIS_PREV_HASH, expectedRoot, message());
            try (prepared; WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch, List.of());
            }

            assertThat(ledger.stateGet(KEY)).hasValue(VALUE);
            assertThat(ledger.stateProofWire(KEY)).hasValue(expectedProof);
            StateProofEnvelope envelope = ledger.stateProofEnvelope("prepared-chain", KEY)
                    .orElseThrow();
            assertThat(envelope.proofSchemaVersion()).isEqualTo(
                    StateProofEnvelope.PROOF_SCHEMA_VERSION);
            assertThat(envelope.proof().presence()).isEqualTo(StateProof.Presence.PRESENT);
            assertThat(envelope.proof().nativeProof()).isEqualTo(expectedProof);
            assertThat(envelope.proof().snapshot().identity().legacy()).isTrue();
            assertThat(envelope.blockHash()).isEqualTo(AppBlockCodec.blockHash(block));
        }

        try (AppLedgerStore reopened = new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-test"))) {
            assertThat(reopened.tipHeight()).isEqualTo(1);
            assertThat(reopened.stateRoot()).isEqualTo(expectedRoot);
            assertThat(reopened.stateProofWire(KEY)).hasValue(expectedProof);
            assertThat(reopened.stateBackend().verifyIntegrity().valid()).isTrue();
        }
    }

    @Test
    void discardedAndStaleCandidatesCannotChangeFinalizedState(@TempDir Path directory) {
        try (AppLedgerStore ledger = new AppLedgerStore(
                directory.resolve("ledger").toString(),
                LoggerFactory.getLogger("prepared-state-test"))) {
            CandidateState discarded = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            discarded.put(KEY, bytes("discarded"));
            discarded.discard();
            assertThat(discarded.closed()).isTrue();
            assertThat(ledger.stateRoot()).isNull();
            assertThat(ledger.stateGet(KEY)).isEmpty();

            StagedStateCommit winner = prepared(ledger, VALUE);
            StagedStateCommit stale = prepared(ledger, bytes("stale"));
            AppBlock winnerBlock = block(1, AppBlock.GENESIS_PREV_HASH,
                    winner.stateRoot(), message());
            try (winner; WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(winnerBlock, AppBlockCodec.blockHash(winnerBlock),
                        winner, batch, List.of());
            }

            AppBlock staleBlock = block(1, AppBlock.GENESIS_PREV_HASH,
                    stale.stateRoot(), message());
            try (stale; WriteBatch staleBatch = new WriteBatch()) {
                assertThatThrownBy(() -> ledger.commitBlock(
                        staleBlock, AppBlockCodec.blockHash(staleBlock),
                        stale, staleBatch, List.of()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("stale");
            }
            assertThat(ledger.tipHeight()).isEqualTo(1);
            assertThat(ledger.stateGet(KEY)).hasValue(VALUE);
        }
    }

    @Test
    void everyPrepareAndCommitFaultBoundaryRestartsAtAnAtomicOldOrNewState(
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
                    ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-fault"),
                    StateCommitmentIdentity.legacyMpf(), fault)) {
                assertThatThrownBy(() -> attemptCommit(ledger))
                        .isInstanceOf(InjectedFault.class)
                        .hasMessageContaining(point.name());
            }

            boolean writeCompleted = point == StateCommitFaultInjector.FaultPoint.AFTER_DURABLE_WRITE
                    || point == StateCommitFaultInjector.FaultPoint.AFTER_COMMIT_VERIFICATION;
            try (AppLedgerStore reopened = new AppLedgerStore(
                    ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-fault"))) {
                assertThat(reopened.tipHeight()).isEqualTo(writeCompleted ? 1 : 0);
                if (writeCompleted) {
                    assertThat(reopened.stateGet(KEY)).hasValue(VALUE);
                } else {
                    assertThat(reopened.stateGet(KEY)).isEmpty();
                }
                assertThat(reopened.messageHeight(message().getMessageId()).isPresent())
                        .isEqualTo(writeCompleted);
                assertThat(reopened.messagesByTopic("topic", 1, 10).isEmpty())
                        .isEqualTo(!writeCompleted);
                assertThat(reopened.verifyIntegrity()).isTrue();
            }
        }
    }

    @Test
    void restartVerificationFaultsCloseTheFailedOpenAndPreserveTheLedger(@TempDir Path directory) {
        Path ledgerPath = directory.resolve("ledger");
        try (AppLedgerStore ledger = new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-restart"))) {
            attemptCommitWithoutFault(ledger);
        }

        for (StateCommitFaultInjector.FaultPoint point : List.of(
                StateCommitFaultInjector.FaultPoint.BEFORE_RESTART_VERIFICATION,
                StateCommitFaultInjector.FaultPoint.AFTER_RESTART_VERIFICATION)) {
            assertThatThrownBy(() -> new AppLedgerStore(
                    ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-restart"),
                    StateCommitmentIdentity.legacyMpf(), new OneShotFault(point)))
                    .isInstanceOf(InjectedFault.class)
                    .hasMessageContaining(point.name());
            try (AppLedgerStore reopened = new AppLedgerStore(
                    ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-restart"))) {
                assertThat(reopened.tipHeight()).isEqualTo(1);
                assertThat(reopened.stateGet(KEY)).hasValue(VALUE);
            }
        }
    }

    @Test
    void explicitProfileAndGenesisAreRetainedInStateAndBackendMetadata(@TempDir Path directory) {
        Path ledgerPath = directory.resolve("ledger");
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF, repeated(3, 32));
        byte[] root;
        try (AppLedgerStore ledger = new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-identity"),
                identity)) {
            CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            new StateCommitmentGuard(identity).apply(1, candidate);
            candidate.put(KEY, VALUE);
            StagedStateCommit prepared = (StagedStateCommit) candidate.prepare();
            root = prepared.stateRoot();
            AppBlock block = block(1, AppBlock.GENESIS_PREV_HASH, root, message());
            try (prepared; WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch, List.of());
            }
            assertThat(ledger.stateGet(StateCommitmentIdentity.markerKey()))
                    .hasValue(identity.canonicalBytes());
            assertThatCode(() -> new StateCommitmentGuard(identity)
                    .verifyRetained(ledger, "prepared-chain"))
                    .doesNotThrowAnyException();
        }

        try (AppLedgerStore reopened = new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-identity"),
                identity)) {
            assertThat(reopened.stateRoot()).isEqualTo(root);
            assertThat(reopened.stateCommitmentIdentity().genesisId())
                    .isEqualTo(identity.genesisId());
        }
        assertThatThrownBy(() -> new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-identity")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy MPF configuration");
        StateCommitmentIdentity differentGenesis = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF, repeated(4, 32));
        try (AppLedgerStore incompatiblePeer = new AppLedgerStore(
                directory.resolve("peer-b").toString(),
                LoggerFactory.getLogger("prepared-state-identity"), differentGenesis)) {
            CandidateState peerCandidate = incompatiblePeer.stateBackend()
                    .beginCandidate(0, new byte[32], 1);
            new StateCommitmentGuard(differentGenesis).apply(1, peerCandidate);
            peerCandidate.put(KEY, VALUE);
            try (StagedStateCommit peerPrepared =
                         (StagedStateCommit) peerCandidate.prepare()) {
                assertThat(peerPrepared.stateRoot()).isNotEqualTo(root);
            }
        }
        assertThatThrownBy(() -> new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-identity"),
                differentGenesis))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    @Test
    void runtimeRejectsAnUnavailableBackendBeforeOpeningRocksDb(@TempDir Path directory) {
        Path ledgerPath = directory.resolve("poseidon-jmt-ledger");
        StateCommitmentIdentity jmt = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.POSEIDON_JMT, repeated(5, 32));
        assertThatThrownBy(() -> new AppLedgerStore(
                ledgerPath.toString(), LoggerFactory.getLogger("prepared-state-jmt"), jmt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not released");
        assertThat(ledgerPath).doesNotExist();
    }

    private static void attemptCommit(AppLedgerStore ledger) {
        CandidateState candidate = null;
        StagedStateCommit prepared = null;
        try {
            candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
            candidate.put(KEY, VALUE);
            prepared = (StagedStateCommit) candidate.prepare();
            AppBlock block = block(1, AppBlock.GENESIS_PREV_HASH,
                    prepared.stateRoot(), message());
            try (WriteBatch batch = new WriteBatch()) {
                ledger.commitBlock(block, AppBlockCodec.blockHash(block),
                        prepared, batch, List.of());
            }
        } finally {
            if (prepared != null) {
                prepared.close();
            } else if (candidate != null) {
                candidate.discard();
            }
        }
    }

    private static void attemptCommitWithoutFault(AppLedgerStore ledger) {
        attemptCommit(ledger);
    }

    private static StagedStateCommit prepared(AppLedgerStore ledger, byte[] value) {
        CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1);
        candidate.put(KEY, value);
        return (StagedStateCommit) candidate.prepare();
    }

    private static AppBlock block(long height, byte[] previousHash, byte[] root, AppMessage message) {
        List<AppMessage> messages = List.of(message);
        return new AppBlock(
                AppBlock.BLOCK_VERSION,
                "prepared-chain",
                height,
                previousHash,
                0,
                new byte[0],
                1_700_000_000_000L + height,
                AppBlockCodec.messagesRoot(messages),
                root,
                messages,
                new byte[32],
                FinalityCert.empty());
    }

    private static AppMessage message() {
        return AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(repeated(7, 32))
                .chainId("prepared-chain")
                .topic("topic")
                .sender(repeated(8, 32))
                .senderSeq(1)
                .expiresAt(0)
                .body(bytes("body"))
                .authScheme(1)
                .authProof(repeated(9, 64))
                .build();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] repeated(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
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

    private static final class MapNodeStore implements NodeStore {
        private final Map<Key, byte[]> nodes = new HashMap<>();

        @Override public byte[] get(byte[] key) {
            byte[] value = nodes.get(new Key(key));
            return value != null ? value.clone() : null;
        }
        @Override public void put(byte[] key, byte[] value) {
            nodes.put(new Key(key), value.clone());
        }
        @Override public void delete(byte[] key) {
            nodes.remove(new Key(key));
        }
    }

    private record Key(byte[] bytes) {
        private Key {
            bytes = bytes.clone();
        }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(bytes, key.bytes);
        }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
}
