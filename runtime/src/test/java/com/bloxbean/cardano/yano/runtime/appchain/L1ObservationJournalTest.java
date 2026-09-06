package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.state.CandidateState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L1ObservationJournalTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyCursorRecoveryRequiresCommittedAuthorityAndPreservesFailureBarrier() {
        L1Observation observation = observation(10, 0, 7);
        byte[] root;
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            commitWithAuthenticatedCursor(ledger, journal, observation);
            root = ledger.stateRoot();
            replaceCursorWithLegacyKey(ledger);
            journal.markCallbackFailure(10);
        }
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            assertThat(journal.status().get("recoveredLegacyCursors")).isEqualTo(1);
            assertThat(ledger.stateRoot()).isEqualTo(root);
            assertThat(journal.healthy()).isFalse();
            assertThat(journal.callbackFailureSlot()).isEqualTo(10);
            assertThat(journal.acknowledge(observation)).isTrue();
            assertThat(journal.acknowledge(observation)).isFalse();
            journal.clearCallbackFailure(10);
            journal.rollback(10);
            assertThat(journal.healthy()).isTrue();
        }
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            assertThat(journal.status().get("recoveredLegacyCursors")).isEqualTo(0);
            assertThat(ledger.stateRoot()).isEqualTo(root);
            assertThatThrownBy(() -> journal.rollback(9)).hasMessageContaining("DEEP_L1_ROLLBACK");
        }
    }

    @Test
    void legacyCursorWithoutAuthenticatedEvidenceFailsClosedWithoutWriting() {
        L1Observation observation = observation(10, 0, 7);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            journal.observe(List.of(observation));
            AppBlock block = blockWith(observation);
            try (WriteBatch batch = new WriteBatch()) {
                journal.stageFinalized(block, batch);
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), block.stateRoot(), batch);
            }
            byte[] legacy = replaceCursorWithLegacyKey(ledger);
            assertThatThrownBy(() -> new L1ObservationJournal(ledger, 1_000_000))
                    .hasMessage("L1_CURSOR_RECOVERY_REQUIRES_COMMITTED_EVIDENCE");
            assertThat(ledger.epochSpoolScan(new byte[]{'C'}, 2).getFirst().value()).isEqualTo(legacy);
        }
    }

    @Test
    void legacyCursorRecoveryRespectsCapacityAndDoesNotClearQuarantine() {
        L1Observation observation = observation(10, 0, 7);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            commitWithAuthenticatedCursor(ledger, journal, observation);
            long canonicalBytes = (long) new L1ObservationJournal(ledger, 1_000_000).status().get("usedBytes");
            byte[] legacy = replaceCursorWithLegacyKey(ledger);
            assertThatThrownBy(() -> new L1ObservationJournal(ledger, canonicalBytes - 1))
                    .hasMessage("L1_CURSOR_RECOVERY_EXCEEDS_CAPACITY");
            assertThat(ledger.epochSpoolScan(new byte[]{'C'}, 2).getFirst().value()).isEqualTo(legacy);
            ledger.epochSpoolWrite(List.of(AppLedgerStore.EpochSpoolMutation.put(new byte[]{'Q'}, new byte[]{1})));
            L1ObservationJournal repaired = new L1ObservationJournal(ledger, 1_000_000);
            assertThat(repaired.status().get("recoveredLegacyCursors")).isEqualTo(1);
            assertThat(repaired.healthy()).isFalse();
            assertThat(ledger.epochSpoolGet(new byte[]{'Q'})).containsExactly(1);
        }
    }

    private static byte[] replaceCursorWithLegacyKey(AppLedgerStore ledger) {
        var entry = ledger.epochSpoolScan(new byte[]{'C'}, 2).getFirst();
        ByteBuffer cursor = ByteBuffer.wrap(entry.value());
        byte[] rawKey = new byte[cursor.getInt()];
        cursor.get(rawKey);
        ledger.epochSpoolWrite(List.of(AppLedgerStore.EpochSpoolMutation.put(entry.key(), rawKey)));
        return rawKey;
    }

    @Test
    void recoveryRejectsWrongCursorIdentityAndUnfinalizedRecordsWithoutPartialRepair() {
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            commitWithAuthenticatedCursor(ledger, journal, observation(10, 0, 7));
            byte[] legacy = replaceCursorWithLegacyKey(ledger);
            byte[] originalKey = ledger.epochSpoolScan(new byte[]{'C'}, 2).getFirst().key();
            byte[] wrongKey = originalKey.clone();
            Arrays.fill(wrongKey, 1, wrongKey.length, (byte) 0xff);
            assertThat(wrongKey).isNotEqualTo(originalKey);
            ledger.epochSpoolWrite(List.of(AppLedgerStore.EpochSpoolMutation.put(wrongKey, legacy)));
            assertThatThrownBy(() -> new L1ObservationJournal(ledger, 1_000_000))
                    .hasMessage("L1_CURSOR_RECOVERY_REQUIRES_COMMITTED_EVIDENCE");
            assertThat(ledger.epochSpoolGet(originalKey)).isEqualTo(legacy);
            assertThat(ledger.epochSpoolGet(wrongKey)).isEqualTo(legacy);

            byte[] record = ledger.epochSpoolGet(legacy);
            record[Integer.BYTES] = (byte) L1ObservationJournal.State.SEEN_UNSTABLE.ordinal();
            ledger.epochSpoolWrite(List.of(AppLedgerStore.EpochSpoolMutation.delete(wrongKey),
                    AppLedgerStore.EpochSpoolMutation.put(legacy, record)));
            assertThatThrownBy(() -> new L1ObservationJournal(ledger, 1_000_000))
                    .hasMessage("L1_CURSOR_RECOVERY_REQUIRES_COMMITTED_EVIDENCE");
            assertThat(ledger.epochSpoolGet(originalKey)).isEqualTo(legacy);
        }
    }

    private static void commitWithAuthenticatedCursor(AppLedgerStore ledger, L1ObservationJournal journal,
                                                       L1Observation observation) {
        journal.observe(List.of(observation));
        AppBlock input = blockWith(observation);
        try (CandidateState candidate = ledger.stateBackend().beginCandidate(0, new byte[32], 1)) {
            L1ObservationJournal.commitAuthenticatedCursors(input, candidate, new byte[32]);
            StagedStateCommit prepared = (StagedStateCommit) candidate.prepare();
            AppBlock block = new AppBlock(input.version(), input.chainId(), input.height(), input.prevHash(),
                    input.l1Slot(), input.l1BlockHash(), input.timestamp(), input.messagesRoot(), prepared.stateRoot(),
                    input.messages(), input.proposer(), input.cert());
            try (WriteBatch batch = new WriteBatch()) {
                journal.stageFinalized(block, batch);
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), prepared, batch, List.of());
            }
        }
    }

    @Test
    void committedCursorSurvivesRestartAcknowledgementAndGuardsDeepRollback() {
        L1Observation observation = observation(10, 0, 7);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            journal.observe(List.of(observation));
            AppBlock block = blockWith(observation);
            try (WriteBatch batch = new WriteBatch()) {
                journal.stageFinalized(block, batch);
                ledger.commitBlock(block, AppBlockCodec.blockHash(block), block.stateRoot(), batch);
            }
        }
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            assertThat(journal.acknowledge(observation)).isTrue();
            assertThat(journal.acknowledge(observation)).isFalse();
            assertThat(journal.pending(20, 10, 1_000_000)).isEmpty();
            journal.rollback(10);
            assertThat(journal.healthy()).isTrue();
            assertThatThrownBy(() -> journal.rollback(9)).hasMessageContaining("DEEP_L1_ROLLBACK");
        }
    }

    @Test
    void pendingSurvivesRestartUntilFinalizedAcknowledgement() {
        L1Observation observation = observation(10, 0, 7);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            journal.observe(List.of(observation));
            assertThat(journal.pending(9, 10, 1_000_000)).isEmpty();
            assertThat(journal.pending(10, 10, 1_000_000)).containsExactly(observation);
        }

        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            assertThat(journal.pending(10, 10, 1_000_000)).containsExactly(observation);
            assertThat(journal.acknowledge(observation)).isTrue();
            assertThat(journal.pending(10, 10, 1_000_000)).isEmpty();
        }
    }

    @Test
    void callbackFailureBarrierSurvivesRestartUntilExactSlotReplays() {
        AtomicBoolean fail = new AtomicBoolean(true);
        L1Observer observer = new L1Observer() {
            @Override public String observerId() { return "restart-observer"; }
            @Override
            public List<L1Observation> observe(long slot, byte[] blockHash,
                                               Block block) {
                if (fail.getAndSet(false)) {
                    throw new IllegalStateException("first attempt");
                }
                return List.of();
            }
        };
        try (AppLedgerStore ledger = store()) {
            L1ObservationService service = new L1ObservationService(
                    List.of(observer), 64,
                    new L1ObservationJournal(ledger, 1_000_000),
                    LoggerFactory.getLogger(L1ObservationJournalTest.class));
            assertThatThrownBy(() -> service.onL1Block(10, filled(1), null))
                    .hasMessage("L1_OBSERVER_CALLBACK_FAILED");
        }
        try (AppLedgerStore ledger = store()) {
            L1ObservationService restarted = new L1ObservationService(
                    List.of(observer), 64,
                    new L1ObservationJournal(ledger, 1_000_000),
                    LoggerFactory.getLogger(L1ObservationJournalTest.class));
            assertThat(restarted.healthy()).isFalse();
            assertThatThrownBy(() -> restarted.onL1Block(11, filled(2), null))
                    .hasMessageContaining("L1_OBSERVER_REPLAY_REQUIRED");
            restarted.onL1Block(10, filled(1), null);
            assertThat(restarted.healthy()).isTrue();
        }
    }

    @Test
    void rollbackPastCallbackFailureClearsBarrierDurably() {
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            journal.markCallbackFailure(10);
            L1ObservationService service = new L1ObservationService(
                    List.of(), 64, journal,
                    LoggerFactory.getLogger(L1ObservationJournalTest.class));
            assertThat(service.healthy()).isFalse();

            service.onL1Rollback(9);

            assertThat(service.healthy()).isTrue();
            assertThat(journal.callbackFailureSlot()).isEqualTo(-1);
        }
        try (AppLedgerStore ledger = store()) {
            assertThat(new L1ObservationJournal(ledger, 1_000_000).healthy()).isTrue();
        }
    }

    @Test
    void ordersBySlotAndOrdinalAndRejectsConflictingSource() {
        L1Observation laterOrdinal = observation(10, 1, 8);
        L1Observation first = observation(10, 0, 7);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            journal.observe(List.of(laterOrdinal, first));
            assertThat(journal.pending(10, 10, 1_000_000))
                    .containsExactly(first, laterOrdinal);

            L1Observation conflict = L1Observation.transaction("observer", filled(4), 0,
                    10, filled(2), new byte[]{99});
            assertThatThrownBy(() -> journal.observe(List.of(conflict)))
                    .hasMessageContaining("CONFLICTING");
        }
    }

    @Test
    void rollbackDeletesPendingButQuarantinesFinalized() {
        L1Observation observation = observation(10, 0, 7);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            journal.observe(List.of(observation));
            journal.rollback(9);
            assertThat(journal.pending(20, 10, 1_000_000)).isEmpty();
            journal.observe(List.of(observation));
            journal.acknowledge(observation);
            assertThatThrownBy(() -> journal.rollback(9))
                    .hasMessageContaining("DEEP_L1_ROLLBACK");
        }
    }

    @Test
    void retainedJournalRejectsAnotherObserverProfile() {
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal =
                    new L1ObservationJournal(ledger, 1_000_000, filled(1));
            journal.observe(List.of(observation(10, 0, 7)));
        }
        try (AppLedgerStore ledger = store()) {
            assertThatThrownBy(() ->
                    new L1ObservationJournal(ledger, 1_000_000, filled(2)))
                    .hasMessageContaining("profile differs");
        }
    }

    @Test
    void preparedObservationIsQuarantinedWhenItsL1FactRollsBack() {
        L1Observation observation = observation(10, 0, 7);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            journal.observe(List.of(observation));
            assertThat(journal.pending(10, 10, 1_000_000)).containsExactly(observation);

            AppBlock block = blockWith(observation);
            journal.markInFlight(block);
            assertThat(journal.status().toString()).contains("IN_FLIGHT=1");
            journal.markPrepared(block);
            assertThat(journal.status().toString()).contains("QC_PREPARED=1");

            assertThatThrownBy(() -> journal.rollback(9))
                    .hasMessageContaining("L1_INVALIDATED_PREPARED_VALUE");
            assertThat(journal.status().toString()).contains("QUARANTINED=1");
            assertThat(journal.pending(20, 10, 1_000_000)).isEmpty();
            assertThat(journal.healthy()).isFalse();
        }
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal restarted = new L1ObservationJournal(ledger, 1_000_000);
            L1ObservationService service = new L1ObservationService(
                    List.of(), 64, restarted,
                    LoggerFactory.getLogger(L1ObservationJournalTest.class));
            assertThat(restarted.healthy()).isFalse();
            assertThat(service.healthy()).isFalse();
        }
    }

    @Test
    void entryLimitRejectsTheWholeObservationBatch() {
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(
                    ledger, 1_000_000, 1, new byte[32]);
            assertThatThrownBy(() -> journal.observe(List.of(
                    observation(10, 0, 7), observation(10, 1, 8))))
                    .hasMessageContaining("ENTRY_CAPACITY");
            assertThat(journal.pending(20, 10, 1_000_000)).isEmpty();
        }
    }

    private AppLedgerStore store() {
        return new AppLedgerStore(tempDir.resolve("ledger").toString(),
                LoggerFactory.getLogger(L1ObservationJournalTest.class),
                TestStateCommitments.MPF);
    }

    private static L1Observation observation(long slot, long ordinal, int claim) {
        return L1Observation.transaction("observer", filled(4), ordinal, slot,
                filled(2), new byte[]{(byte) claim});
    }

    private static AppBlock blockWith(L1Observation observation) {
        byte[] sender = filled(9);
        byte[] body = observation.encode();
        long expiresAt = Long.MAX_VALUE;
        byte[] messageId = AppMessage.computeMessageId("journal-test", observation.topic(),
                sender, 1, expiresAt, body);
        AppMessage message = AppMessage.builder()
                .messageId(messageId)
                .chainId("journal-test")
                .topic(observation.topic())
                .sender(sender)
                .senderSeq(1)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(new byte[64])
                .build();
        return new AppBlock(AppBlock.BLOCK_VERSION, "journal-test", 1,
                AppBlock.GENESIS_PREV_HASH, 10, observation.blockHash(), 1,
                new byte[32], new byte[32], List.of(message), sender,
                FinalityCert.empty());
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
