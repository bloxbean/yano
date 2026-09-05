package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTick;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTopics;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationJournalTest {

    @Test
    void heartbeatOutboxSurvivesRestartAndReplacesOnlyOneBoundedEntry(@TempDir Path directory) {
        byte[] body = new ObservationTick(1, ObservationAnchorType.VERIFIED_L1_SLOT, 100).encode();
        AppMessage expected = heartbeat(body, 1);
        try (AppLedgerStore ledger = ledger(directory)) {
            ObservationJournal journal = new ObservationJournal(ledger, filled(1), "chain",
                    filled(2), filled(3), 10, 4096);
            assertThat(journal.pendingTick(body, () -> expected).getMessageId()).isEqualTo(expected.getMessageId());
            assertThat(journal.entries()).isEqualTo(1);
        }
        try (AppLedgerStore ledger = ledger(directory)) {
            ObservationJournal journal = new ObservationJournal(ledger, filled(1), "chain",
                    filled(2), filled(3), 10, 4096);
            assertThat(journal.pendingTick(body, () -> { throw new AssertionError("must reuse durable tick"); })
                    .getMessageId()).isEqualTo(expected.getMessageId());
            byte[] later = new ObservationTick(1, ObservationAnchorType.VERIFIED_L1_SLOT, 101).encode();
            assertThat(journal.pendingTick(later, () -> heartbeat(later, 2)).getSenderSeq()).isEqualTo(2);
            assertThat(journal.entries()).isEqualTo(1);
            assertThat(journal.bytes()).isLessThan(2048);
        }
    }

    private static AppMessage heartbeat(byte[] body, long sequence) {
        byte[] sender = filled(2);
        return AppMessage.builder().messageId(AppMessage.computeMessageId("chain", ObservationTopics.TICK,
                        sender, sequence, Long.MAX_VALUE, body))
                .chainId("chain").topic(ObservationTopics.TICK).sender(sender).senderSeq(sequence)
                .expiresAt(Long.MAX_VALUE).body(body).authScheme(0).authProof(new byte[64]).build();
    }

    @Test
    void restartAfterPreparingSigningMaterialCannotSelectAnotherValue(@TempDir Path directory) {
        ObservationReport original = report(filled(2), new byte[]{7});
        try (AppLedgerStore ledger = ledger(directory)) {
            ObservationJournal journal = new ObservationJournal(ledger, filled(1), "chain",
                    filled(2), filled(3), 100, 1024 * 1024);
            assertThat(journal.prepareLocalReport(original).encode()).isEqualTo(original.encode());
        }
        try (AppLedgerStore ledger = ledger(directory)) {
            ObservationJournal journal = new ObservationJournal(ledger, filled(1), "chain",
                    filled(2), filled(3), 100, 1024 * 1024);
            assertThat(journal.prepareLocalReport(report(filled(2), new byte[]{8})).encode())
                    .isEqualTo(original.encode());
            assertThat(journal.retainedRounds(10)).hasSize(1);
            journal.markTerminal(original.subscriptionId(), original.roundNumber());
            assertThat(journal.entries()).isZero();
        }
    }

    @Test
    void signingLockAndReportAreDurableAndIdentityBound(@TempDir Path directory) {
        byte[] genesis = filled(1);
        byte[] reporter = filled(2);
        byte[] profile = filled(3);
        ObservationReport first = report(reporter, new byte[]{7});
        Path path = directory.resolve("ledger");

        try (AppLedgerStore ledger = ledger(path)) {
            ObservationJournal journal = new ObservationJournal(ledger, genesis, "chain",
                    reporter, profile, 100, 1024 * 1024);
            assertThat(journal.persistReport(first)).isTrue();
            assertThat(journal.persistReport(first)).isFalse();
            assertThat(journal.reports(first.subscriptionId(), first.roundNumber(), 10))
                    .hasSize(1);

            ObservationReport equivocation = report(reporter, new byte[]{8});
            assertThatThrownBy(() -> journal.persistReport(equivocation))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("equivocation");
            assertThatThrownBy(() -> new ObservationJournal(ledger, genesis, "chain",
                    filled(9), profile, 100, 1024 * 1024))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("another chain, profile, or node identity");
        }

        try (AppLedgerStore reopened = ledger(path)) {
            ObservationJournal journal = new ObservationJournal(reopened, genesis, "chain",
                    reporter, profile, 100, 1024 * 1024);
            assertThat(journal.reports(first.subscriptionId(), first.roundNumber(), 10))
                    .singleElement().satisfies(retained ->
                    assertThat(retained.encode()).isEqualTo(first.encode()));
            assertThat(journal.persistReport(first)).isFalse();
        }
    }

    static AppLedgerStore ledger(Path path) {
        return new AppLedgerStore(path.toString(),
                LoggerFactory.getLogger(ObservationJournalTest.class),
                TestStateCommitments.MPF);
    }

    static ObservationReport report(byte[] reporter, byte[] value) {
        return new ObservationReport(1, filled(1), "chain", filled(4), filled(3),
                filled(5), filled(6), 0, filled(7), filled(8), reporter,
                new byte[]{1}, value, new byte[0], new byte[]{1}, 0, 1,
                new byte[64]);
    }

    static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
