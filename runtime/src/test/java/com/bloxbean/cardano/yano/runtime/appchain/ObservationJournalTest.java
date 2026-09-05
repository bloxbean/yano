package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationJournalTest {

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

    private static AppLedgerStore ledger(Path path) {
        return new AppLedgerStore(path.toString(),
                LoggerFactory.getLogger(ObservationJournalTest.class),
                TestStateCommitments.MPF);
    }

    private static ObservationReport report(byte[] reporter, byte[] value) {
        return new ObservationReport(1, filled(1), "chain", filled(4), filled(3),
                filled(5), filled(6), 0, filled(7), filled(8), reporter,
                new byte[]{1}, value, new byte[0], new byte[]{1}, 0, 1,
                new byte[64]);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
