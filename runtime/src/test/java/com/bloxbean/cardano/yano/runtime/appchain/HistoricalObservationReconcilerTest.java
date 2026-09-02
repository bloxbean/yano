package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.l1view.HistoricalObservationPointer;
import com.bloxbean.cardano.yano.api.appchain.l1view.HistoricalObservationResolver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoricalObservationReconcilerTest {
    @TempDir
    Path directory;

    @Test
    void acceptsOnlyConfirmedPointerWithIndependentAgreement() {
        L1Observation observation = L1Observation.transaction(
                "deposits", filled(1), 3, 42, filled(2), new byte[]{7});
        HistoricalObservationPointer pointer = pointer(observation, true);
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            HistoricalObservationReconciler reconciler = new HistoricalObservationReconciler(
                    List.of(resolver("archive-a", observation),
                            resolver("archive-b", observation)), 2, journal);
            assertThat(reconciler.reconcile(pointer)).isEqualTo(observation);
            assertThat(journal.pending(42, 10, 1_000_000)).containsExactly(observation);
        }
    }

    @Test
    void rejectsCallerWithoutConfirmationAndResolverDisagreement() {
        L1Observation observation = L1Observation.transaction(
                "deposits", filled(1), 3, 42, filled(2), new byte[]{7});
        L1Observation conflicting = L1Observation.transaction(
                "deposits", filled(1), 3, 42, filled(2), new byte[]{8});
        try (AppLedgerStore ledger = store()) {
            L1ObservationJournal journal = new L1ObservationJournal(ledger, 1_000_000);
            HistoricalObservationReconciler reconciler = new HistoricalObservationReconciler(
                    List.of(resolver("archive-a", observation),
                            resolver("archive-b", conflicting)), 2, journal);
            assertThatThrownBy(() -> reconciler.reconcile(pointer(observation, false)))
                    .hasMessageContaining("confirmation");
            assertThatThrownBy(() -> reconciler.reconcile(pointer(observation, true)))
                    .hasMessageContaining("DISAGREEMENT");
        }
    }

    private AppLedgerStore store() {
        return new AppLedgerStore(directory.resolve("ledger").toString(),
                LoggerFactory.getLogger(getClass()), TestStateCommitments.MPF);
    }

    private static HistoricalObservationPointer pointer(L1Observation observation,
                                                        boolean confirmed) {
        return new HistoricalObservationPointer("chain", observation.observerId(), "preview",
                observation.anchor(), Optional.of(observation.slot()),
                Optional.of(observation.blockHash()), observation.eventOrdinal(), confirmed);
    }

    private static HistoricalObservationResolver resolver(String id,
                                                          L1Observation observation) {
        return new HistoricalObservationResolver() {
            @Override public String resolverId() { return id; }
            @Override public Optional<L1Observation> resolve(HistoricalObservationPointer pointer) {
                return Optional.of(observation);
            }
        };
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
