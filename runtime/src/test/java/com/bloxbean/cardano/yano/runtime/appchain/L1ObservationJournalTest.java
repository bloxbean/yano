package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class L1ObservationJournalTest {

    @TempDir
    Path tempDir;

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
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
