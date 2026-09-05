package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.AppObservationEmitter;
import com.bloxbean.cardano.yano.api.appchain.observation.ExactValueQuorumPolicy;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationAnchorType;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCertificateVerifier;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationIntent;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscription;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationSubscriptionId;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationKernelTest {
    private static final String CHAIN_ID = "observation-kernel-test";
    private static final String MEMBER_SEED = "21".repeat(32);

    @Test
    void createsOpensCertifiesAndDeduplicatesOneShotObservation(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AppStateMachine machine = machine(emitted, callbacks);

        apply(fixture, machine, 1, List.of());
        ObservationSubscription subscription = fixture.ledger.observationReader()
                .subscription(emitted.get().bytes()).orElseThrow();
        assertThat(subscription.applicationId()).isEqualTo("test-app");
        assertThat(fixture.ledger.observationReader().activeCount()).isEqualTo(1);

        apply(fixture, machine, 2, List.of());
        ObservationRound round = fixture.ledger.observationReader()
                .round(subscription.subscriptionId(), 0).orElseThrow();
        assertThat(round.openingHeight()).isEqualTo(2);
        assertThat(fixture.ledger.observationReader().openRoundCount()).isEqualTo(1);

        ObservationCertificate certificate = certificate(fixture, signer, round,
                "delivered".getBytes(StandardCharsets.UTF_8));
        assertCertificateValid(fixture, signer, round, certificate);
        apply(fixture, machine, 3, List.of(resultMessage(certificate, 1)));

        ObservationSubscription completed = fixture.ledger.observationReader()
                .subscription(subscription.subscriptionId()).orElseThrow();
        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(callbacks).hasValue(1);
        assertThat(fixture.ledger.observationReader().activeCount()).isZero();
        assertThat(fixture.ledger.observationReader().openRoundCount()).isZero();

        // A canonical old certificate is an audit no-op, even with a fresh
        // member envelope and sender sequence.
        apply(fixture, machine, 4, List.of(resultMessage(certificate, 2)));
        assertThat(callbacks).hasValue(1);
        fixture.close();
    }

    @Test
    void conflictingCertificatesForActiveRoundRejectWholeBlock(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AppStateMachine machine = machine(emitted, new AtomicInteger());
        apply(fixture, machine, 1, List.of());
        apply(fixture, machine, 2, List.of());
        ObservationRound round = fixture.ledger.observationReader()
                .round(emitted.get().bytes(), 0).orElseThrow();
        ObservationCertificate left = certificate(fixture, signer, round, new byte[]{1});
        ObservationCertificate right = certificate(fixture, signer, round, new byte[]{2});
        List<ObservationCertificate> ordered = new ArrayList<>(List.of(left, right));
        ordered.sort((first, second) -> Arrays.compareUnsigned(first.resultId(), second.resultId()));

        assertThatThrownBy(() -> apply(fixture, machine, 3, List.of(
                resultMessage(ordered.get(0), 1), resultMessage(ordered.get(1), 2))))
                .hasRootCauseMessage(
                        "conflicting valid results for one active observation round");
        assertThat(fixture.ledger.tipHeight()).isEqualTo(2);
        fixture.close();
    }

    @Test
    void resultMustOccupyCanonicalRegionAndMalformedOldResultStillRejects(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AppStateMachine machine = machine(emitted, new AtomicInteger());
        apply(fixture, machine, 1, List.of());
        apply(fixture, machine, 2, List.of());
        ObservationRound round = fixture.ledger.observationReader()
                .round(emitted.get().bytes(), 0).orElseThrow();
        ObservationCertificate certificate = certificate(fixture, signer, round, new byte[]{7});

        assertThatThrownBy(() -> apply(fixture, machine, 3, List.of(
                message("ordinary", new byte[0], 1), resultMessage(certificate, 2))))
                .hasRootCauseMessage(
                        "observation results must immediately follow the canonical L1 prefix");

        apply(fixture, machine, 3, List.of(resultMessage(certificate, 3)));
        assertThatThrownBy(() -> apply(fixture, machine, 4, List.of(
                message("~obs/result/v1", new byte[]{(byte) 0xff}, 4))))
                .hasStackTraceContaining("malformed ~obs/result/v1 input");
        fixture.close();
    }

    @Test
    void timeoutExpiresOnlyAfterInclusiveInclusionGrace(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AppStateMachine machine = machine(emitted, callbacks);
        apply(fixture, machine, 1, List.of());
        apply(fixture, machine, 2, List.of());
        for (long height = 3; height <= 7; height++) {
            apply(fixture, machine, height, List.of());
        }
        assertThat(callbacks).hasValue(0);
        assertThat(fixture.ledger.observationReader().openRoundCount()).isEqualTo(1);

        apply(fixture, machine, 8, List.of());
        ObservationSubscription expired = fixture.ledger.observationReader()
                .subscription(emitted.get().bytes()).orElseThrow();
        assertThat(expired.status().name()).isEqualTo("EXPIRED");
        assertThat(callbacks).hasValue(1);
        assertThat(fixture.ledger.observationReader().openRoundCount()).isZero();
        fixture.close();
    }

    @Test
    void cancellationIsTerminalAuditOnlyAndIdempotent(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AppStateMachine base = machine(emitted, callbacks);
        apply(fixture, base, 1, List.of());
        AppStateMachine cancelling = new AppStateMachine() {
            @Override public String id() { return base.id(); }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) {
            }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects,
                                        AppObservationEmitter observations) {
                observations.cancel(emitted.get());
                observations.cancel(emitted.get());
            }
        };
        apply(fixture, cancelling, 2, List.of());
        ObservationSubscription cancelled = fixture.ledger.observationReader()
                .subscription(emitted.get().bytes()).orElseThrow();
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(callbacks).hasValue(0);
        assertThat(fixture.ledger.observationReader().activeCount()).isZero();
        fixture.close();
    }

    @Test
    void recurringRoundsRetainScheduledCadenceAndWindowAcrossRestart(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        Fixture fixture = fixture(directory, signer, true);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        List<ObservationResult> results = new ArrayList<>();
        AppStateMachine machine = recurringMachine(emitted, results, 0);
        apply(fixture, machine, 1, List.of());
        apply(fixture, machine, 2, List.of());
        byte[] id = emitted.get().bytes();
        ObservationRound first = fixture.ledger.observationReader().round(id, 0).orElseThrow();
        assertThat(first.reportDeadlineAnchor()).isEqualTo(3);
        assertThat(first.absoluteMaxRoundHeight()).isEqualTo(22);
        apply(fixture, machine, 3, List.of(resultMessage(
                certificate(fixture, signer, first, new byte[]{1}), 1)));
        ObservationSubscription pending = fixture.ledger.observationReader().subscription(id).orElseThrow();
        assertThat(pending.version()).isEqualTo(2);
        assertThat(pending.reportWindow()).isEqualTo(1);
        assertThat(pending.nextDueAnchor()).isEqualTo(4);
        assertThat(pending.nextRoundNumber()).isEqualTo(1);
        assertThat(fixture.ledger.observationReader().openRounds(10)).isEmpty();
        fixture.close();

        fixture = fixture(directory, signer, true);
        apply(fixture, machine, 4, List.of());
        ObservationRound second = fixture.ledger.observationReader().round(id, 1).orElseThrow();
        assertThat(second.dueAnchor()).isEqualTo(4);
        assertThat(second.reportDeadlineAnchor()).isEqualTo(5);
        assertThat(second.openingHeight()).isEqualTo(4);
        for (long height = 5; height <= 10; height++) apply(fixture, machine, height, List.of());
        assertThat(results).extracting(ObservationResult::status)
                .containsExactly(ObservationResultStatus.VALUE, ObservationResultStatus.EXPIRED);
        assertThat(fixture.ledger.observationReader().subscription(id).orElseThrow().nextDueAnchor())
                .isEqualTo(6);
        assertThat(fixture.ledger.observationReader().activeCount("test-app")).isEqualTo(1);
        // Round 1 occupied its whole grace: round 2 retains due=6, not now=11.
        apply(fixture, machine, 11, List.of());
        ObservationRound third = fixture.ledger.observationReader().round(id, 2).orElseThrow();
        assertThat(third.dueAnchor()).isEqualTo(6);
        assertThat(third.reportDeadlineAnchor()).isEqualTo(6); // clipped to expiry
        assertThat(third.openingHeight()).isEqualTo(11);
        for (long height = 12; height <= 16; height++) apply(fixture, machine, height, List.of());
        assertThat(fixture.ledger.observationReader().activeCount()).isZero();
        assertThat(fixture.ledger.observationReader().activeCount("test-app")).isZero();
        assertThat(fixture.ledger.observationReader().activeCount(fixture.definition.digest())).isZero();
        assertThat(fixture.ledger.observationReader().openRounds(10)).isEmpty();
        assertThat(results).hasSize(3);
        fixture.close();
    }

    @Test
    void retainedCertificateSurvivesOmissionUntilInclusiveGraceBoundary(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        try (Fixture fixture = fixture(directory, signer, true)) {
            AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
            List<ObservationResult> results = new ArrayList<>();
            AppStateMachine machine = recurringMachine(emitted, results, 1);
            apply(fixture, machine, 1, List.of());
            apply(fixture, machine, 2, List.of());
            ObservationRound round = fixture.ledger.observationReader()
                    .round(emitted.get().bytes(), 0).orElseThrow();
            ObservationCertificate retained = certificate(fixture, signer, round, new byte[]{9});
            // A certificate acquired in-window remains eligible even when proposals
            // omit it through the deadline and most of the bounded inclusion grace.
            long closureHeight = round.reportDeadlineAnchor() + 1;
            long lastEligible = Math.min(round.absoluteMaxRoundHeight(),
                    closureHeight + round.resultInclusionGraceHeights());
            for (long height = 3; height < lastEligible; height++) {
                apply(fixture, machine, height, List.of());
            }
            assertThat(results).isEmpty();
            assertThat(fixture.ledger.observationReader().round(emitted.get().bytes(), 0)
                    .orElseThrow().resultExpiryHeight()).isEqualTo(lastEligible);
            apply(fixture, machine, lastEligible, List.of(resultMessage(retained, 1)));
            assertThat(results).extracting(ObservationResult::status)
                    .containsExactly(ObservationResultStatus.VALUE);
            assertThat(fixture.ledger.observationReader().activeCount()).isZero();
        }
    }

    @Test
    void firstValueCompletionDoesNotRecur(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        try (Fixture fixture = fixture(directory, signer, true)) {
            AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
            AppStateMachine machine = recurringMachine(emitted, new ArrayList<>(), 1);
            apply(fixture, machine, 1, List.of());
            apply(fixture, machine, 2, List.of());
            ObservationRound round = fixture.ledger.observationReader()
                    .round(emitted.get().bytes(), 0).orElseThrow();
            apply(fixture, machine, 3, List.of(resultMessage(
                    certificate(fixture, signer, round, new byte[]{1}), 1)));
            assertThat(fixture.ledger.observationReader().activeCount()).isZero();
            assertThat(fixture.ledger.observationReader().dueAtOrBefore(
                    ObservationAnchorType.APP_HEIGHT, Long.MAX_VALUE, 10)).isEmpty();
        }
    }

    private static AppStateMachine recurringMachine(AtomicReference<ObservationSubscriptionId> emitted,
                                                     List<ObservationResult> results,
                                                     int completionPolicy) {
        return new AppStateMachine() {
            @Override public String id() { return "test-app"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects, AppObservationEmitter observations) {
                if (context.block().height() == 1) {
                    emitted.set(observations.watch(new ObservationIntent("delivery", "recurring",
                            new byte[0], ObservationAnchorType.APP_HEIGHT, 2, 3, 6, 2,
                            completionPolicy)));
                }
            }
            @Override public void onObservationResult(AppBlockExecutionContext context,
                                                       ObservationResult result, AppStateWriter writer,
                                                       AppEffectEmitter effects,
                                                       AppObservationEmitter observations) {
                results.add(result);
            }
        };
    }

    @Test
    void missingDueIndexFailsAuditAndOfflineReplayRestoresIt(@TempDir Path directory) throws Exception {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        try (Fixture source = fixture(directory.resolve("source"), signer, true);
             Fixture restored = fixture(directory.resolve("restored"), signer, true)) {
            AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
            AppStateMachine machine = recurringMachine(emitted, new ArrayList<>(), 0);
            apply(source, machine, 1, List.of());
            source.ledger.verifyObservationIndexes();
            Field dbField = AppLedgerStore.class.getDeclaredField("db");
            Field cfField = AppLedgerStore.class.getDeclaredField("observationsCf");
            dbField.setAccessible(true);
            cfField.setAccessible(true);
            RocksDB db = (RocksDB) dbField.get(source.ledger);
            ColumnFamilyHandle cf = (ColumnFamilyHandle) cfField.get(source.ledger);
            db.delete(cf, ByteBuffer.allocate(42).put((byte) 'd').put((byte) 0)
                    .putLong(2).put(emitted.get().bytes()).array());
            assertThatThrownBy(source.ledger::verifyObservationIndexes)
                    .hasMessageContaining("Missing or invalid observation due index");
            assertThat(ObservationLedgerRebuilder.replay(source.ledger, restored.ledger,
                    restored.kernel, recurringMachine(new AtomicReference<>(), new ArrayList<>(), 0)))
                    .isEqualTo(1);
            assertThat(restored.ledger.stateRoot()).isEqualTo(source.ledger.stateRoot());
            assertThat(restored.ledger.observationReader().dueAtOrBefore(
                    ObservationAnchorType.APP_HEIGHT, 2, 1)).hasSize(1);
            assertThatThrownBy(source.ledger::verifyObservationIndexes)
                    .hasMessageContaining("Missing or invalid observation due index");
            byte[] retainedSafetyState = new byte[]{7, 8, 9};
            source.ledger.observationRuntimePutSync(new byte[]{'z'}, retainedSafetyState);
            ObservationLedgerRebuilder.install(restored.ledger, source.ledger);
            source.ledger.verifyObservationIndexes();
            source.ledger.requireObservationLedgerRunnable();
            assertThat(source.ledger.observationRuntimeGet(new byte[]{'z'})).isEqualTo(retainedSafetyState);
            assertThatThrownBy(restored.ledger::requireObservationLedgerRunnable)
                    .hasMessageContaining("not a runnable ledger");
        }
    }

    @Test
    void slotHighWaterSurvivesZeroReferencesAndTickCannotAdvanceTime(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AppStateMachine machine = new AppStateMachine() {
            @Override public String id() { return "test-app"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects, AppObservationEmitter observations) {
                if (context.block().height() == 1) {
                    emitted.set(observations.watch(new ObservationIntent("delivery", "slots", new byte[0],
                            ObservationAnchorType.VERIFIED_L1_SLOT, 100, 105, 110, 10, 0)));
                }
            }
        };
        AppMessage fakeClock = message("~obs/tick/v1", new ObservationTick(1,
                ObservationAnchorType.VERIFIED_L1_SLOT, Long.MAX_VALUE).encode(), 1);
        try (Fixture fixture = fixture(directory, signer, true, true)) {
            apply(fixture, machine, 1, List.of(), 90);
            apply(fixture, machine, 2, List.of(fakeClock), 0);
            assertThat(fixture.ledger.observationReader().highWaterSlot()).isEqualTo(90);
            assertThat(fixture.ledger.observationReader().openRoundCount()).isZero();
            apply(fixture, machine, 3, List.of(), 100);
            apply(fixture, machine, 4, List.of(), 0);
            assertThat(fixture.ledger.observationReader().highWaterSlot()).isEqualTo(100);
            ObservationRound round = fixture.ledger.observationReader()
                    .round(emitted.get().bytes(), 0).orElseThrow();
            assertThat(round.reportDeadlineAnchor()).isEqualTo(105);
            assertThat(round.resultExpiryHeight()).isZero();
            fixture.ledger.verifyObservationIndexes();
        }
        try (Fixture fixture = fixture(directory, signer, true, true)) {
            assertThat(fixture.ledger.observationReader().highWaterSlot()).isEqualTo(100);
            assertThatThrownBy(() -> apply(fixture, machine, 5,
                    List.of(fakeClock, fakeClock), 106))
                    .hasRootCauseMessage("observation tick bound exceeded");
            apply(fixture, machine, 5, List.of(), 106);
            apply(fixture, machine, 6, List.of(), 0);
            ObservationRound round = fixture.ledger.observationReader()
                    .round(emitted.get().bytes(), 0).orElseThrow();
            assertThat(round.resultExpiryHeight()).isEqualTo(8);
            assertThat(fixture.ledger.observationReader().highWaterSlot()).isEqualTo(106);
            fixture.ledger.verifyObservationIndexes();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "YANO_OBSERVATION_SCALE", matches = "1")
    void recoversOneHundredThousandCommittedSubscriptionsWithBoundedOpening(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        AppStateMachine machine = new AppStateMachine() {
            @Override public String id() { return "scale-app"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects, AppObservationEmitter observations) {
                if (context.block().height() > 100) return;
                for (int index = 0; index < 1000; index++) {
                    long due = context.block().height() == 1 && index < 20 ? 101 : 1_000_000;
                    observations.watch(ObservationIntent.oneShot("delivery", "scale", new byte[0],
                            ObservationAnchorType.APP_HEIGHT, due, due + 10, due + 10));
                }
            }
        };
        byte[] root;
        try (Fixture fixture = fixture(directory, signer, true, false, 100_000)) {
            for (long height = 1; height <= 100; height++) apply(fixture, machine, height, List.of());
            apply(fixture, machine, 101, List.of());
            assertThat(fixture.ledger.observationReader().activeCount()).isEqualTo(100_000);
            assertThat(fixture.ledger.observationReader().openRounds(11)).hasSize(10);
            assertThat(fixture.ledger.observationReader().dueAtOrBefore(
                    ObservationAnchorType.APP_HEIGHT, 101, 7)).hasSize(7);
            root = fixture.ledger.stateRoot();
        }
        try (Fixture fixture = fixture(directory, signer, true, false, 100_000)) {
            assertThat(fixture.ledger.stateRoot()).isEqualTo(root);
            fixture.ledger.verifyObservationIndexes();
            assertThat(fixture.ledger.observationReader().activeCount("scale-app")).isEqualTo(100_000);
            apply(fixture, machine, 102, List.of());
            assertThat(fixture.ledger.observationReader().openRounds(21)).hasSize(20);
            assertThat(fixture.ledger.observationReader().dueAtOrBefore(
                    ObservationAnchorType.APP_HEIGHT, 102, 10)).isEmpty();
        }
    }

    @Test
    void finalizedObservationIndexesAreAtomicAtEveryCommitBoundary(@TempDir Path directory) {
        for (StateCommitFaultInjector.FaultPoint point : StateCommitFaultInjector.FaultPoint.values()) {
            if (point == StateCommitFaultInjector.FaultPoint.BEFORE_RESTART_VERIFICATION
                    || point == StateCommitFaultInjector.FaultPoint.AFTER_RESTART_VERIFICATION) continue;
            verifyCommitBoundary(point, directory.resolve(point.name()));
        }
    }

    private void verifyCommitBoundary(StateCommitFaultInjector.FaultPoint boundary, Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        AtomicBoolean armed = new AtomicBoolean();
        AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
        AppStateMachine machine = recurringMachine(emitted, new ArrayList<>(), 0);
        try (Fixture fixture = fixture(directory, signer, true, false, 100, point -> {
            if (armed.get() && point == boundary) throw new IllegalStateException("injected failure");
        })) {
            apply(fixture, machine, 1, List.of());
            armed.set(true);
            assertThatThrownBy(() -> apply(fixture, machine, 2, List.of()))
                    .hasStackTraceContaining("injected failure");
        }
        try (Fixture fixture = fixture(directory, signer, true)) {
            boolean committed = boundary == StateCommitFaultInjector.FaultPoint.AFTER_DURABLE_WRITE
                    || boundary == StateCommitFaultInjector.FaultPoint.AFTER_COMMIT_VERIFICATION;
            assertThat(fixture.ledger.tipHeight()).isEqualTo(committed ? 2 : 1);
            assertThat(fixture.ledger.observationReader().openRoundCount()).isEqualTo(committed ? 1 : 0);
            assertThat(fixture.ledger.observationReader().round(emitted.get().bytes(), 0).isPresent())
                    .isEqualTo(committed);
            assertThat(fixture.ledger.observationReader().dueAtOrBefore(
                    ObservationAnchorType.APP_HEIGHT, 2, 10)).hasSize(committed ? 0 : 1);
            fixture.ledger.verifyObservationIndexes();
        }
    }

    @Test
    void roundsPinFiveMemberFaultBoundAndAdoptNextMembershipQuorum(@TempDir Path directory) {
        List<AppMessageSigner> original = IntStream.rangeClosed(21, 25)
                .mapToObj(seed -> new AppMessageSigner(String.format("%02x", seed).repeat(32))).toList();
        List<AppMessageSigner> replacement = new ArrayList<>(original.subList(1, 5));
        replacement.add(new AppMessageSigner("26".repeat(32)));
        replacement.add(new AppMessageSigner("27".repeat(32)));
        AppChainMembershipEpoch oldEpoch = new AppChainMembershipEpoch(0,
                original.stream().map(AppMessageSigner::publicKeyHex).toList(), 4);
        AppChainMembershipEpoch newEpoch = new AppChainMembershipEpoch(3,
                replacement.stream().map(AppMessageSigner::publicKeyHex).toList(), 5);
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex("15".repeat(32))
                .memberKeysHex(original.stream().map(AppMessageSigner::publicKeyHex).collect(Collectors.toSet()))
                .proposerKeyHex(original.getFirst().publicKeyHex()).threshold(4)
                .pluginSettings(Map.of("consensus.max-byzantine-members", "1"))
                .stateCommitmentIdentity(TestStateCommitments.MPF).build();
        EffectsSettings effects = EffectsSettings.from(config);
        AppChainConsensusProfile consensus = effects.consensusProfile(config);
        ObservationDefinition definition = new ObservationDefinition(1, "delivery", 1, filled(1), filled(2),
                filled(3), filled(4), ObservationReporterMode.ACTIVE_MEMBERS,
                ObservationHashes.activeMemberRuleDigest(), 1, 4, 1, false, "test", filled(5), "identity-v1",
                ObservationSettings.RAW_EXACT_EVIDENCE, ObservationSettings.EXACT_POLICY,
                filled(6), filled(7), "one-source-v1", "source-version-v1", "digest-v1",
                1, 1024, 1024, 1024, 6, 1);
        ObservationProfileV1 profile = new ObservationProfileV1(1, true, 1, 2, 1, 1, 2, 1, 1,
                List.of(definition), 100, 10, 100, 10, 100, 6, 1,
                4096, 1024, 8192, 10, 16_384, 1, 20, 3);
        ObservationKernel observations = new ObservationKernel(profile, TestStateCommitments.MPF.genesisId(),
                CHAIN_ID, AppChainConsensusProfileCommitment.digest(consensus), 1,
                height -> height < 3 ? oldEpoch : newEpoch,
                ObservationKernel.exactValueRegistry((ignoredDefinition, ignoredRound, report) -> true));
        SystemInputKernel kernel = new SystemInputKernel(effects, new ConsensusProfileGuard(consensus),
                new ObservationProfileGuard(profile), observations);
        try (Fixture fixture = new Fixture(new AppLedgerStore(directory.resolve("ledger").toString(),
                LoggerFactory.getLogger(ObservationKernelTest.class), TestStateCommitments.MPF),
                kernel, definition, profile, consensus)) {
            AtomicReference<ObservationSubscriptionId> emitted = new AtomicReference<>();
            AppStateMachine machine = recurringMachine(emitted, new ArrayList<>(), 0);
            apply(fixture, machine, 1, List.of());
            apply(fixture, machine, 2, List.of());
            ObservationRound first = fixture.ledger.observationReader().round(emitted.get().bytes(), 0).orElseThrow();
            assertThat(first.maxByzantineMembers()).isEqualTo(1);
            assertThat(first.memberCount()).isEqualTo(5);
            ObservationCertificate before = certificate(fixture, original.subList(0, 4), first);
            ObservationCertificate otherSubset = certificate(fixture, original.subList(1, 5), first);
            assertThat(before.resultId()).isEqualTo(otherSubset.resultId());
            // Removed member 0 remains authorized for the bounded old round.
            apply(fixture, machine, 3, List.of(resultMessage(before, 1)));
            apply(fixture, machine, 4, List.of());
            ObservationRound second = fixture.ledger.observationReader().round(emitted.get().bytes(), 1).orElseThrow();
            assertThat(second.membershipEpoch()).isEqualTo(3);
            assertThat(second.reportThreshold()).isEqualTo(5);
            assertThat(second.reporterCount()).isEqualTo(6);
            List<AppMessageSigner> removedSignerSubset = new ArrayList<>(replacement.subList(0, 4));
            removedSignerSubset.add(original.getFirst());
            assertThatThrownBy(() -> apply(fixture, machine, 5,
                    List.of(resultMessage(certificate(fixture, removedSignerSubset, second), 2))))
                    .hasRootCauseMessage("invalid active-round observation certificate");
            apply(fixture, machine, 5,
                    List.of(resultMessage(certificate(fixture, replacement.subList(0, 5), second), 3)));
            fixture.ledger.verifyObservationIndexes();
        }
    }

    private static ObservationCertificate certificate(Fixture fixture,
                                                       List<AppMessageSigner> reporters,
                                                       ObservationRound round) {
        List<ObservationReport> reports = reporters.stream()
                .map(signer -> certificate(fixture, signer, round, new byte[]{7}).reports().getFirst())
                .sorted((left, right) -> Arrays.compareUnsigned(left.reporterPublicKey(), right.reporterPublicKey()))
                .toList();
        ObservationCertificate base = certificate(fixture, reporters.getFirst(), round, new byte[]{7});
        return new ObservationCertificate(1, base.subscriptionId(), base.roundNumber(), base.membershipDigest(),
                base.definitionDigest(), base.policyDigest(), base.sourceSetDigest(), reports, base.output(),
                base.policyTrace(), base.resultId());
    }

    @Test
    void creationAndPersistentApplicationQuotasFailAtomically(@TempDir Path directory) {
        AppMessageSigner signer = new AppMessageSigner(MEMBER_SEED);
        try (Fixture fixture = fixture(directory.resolve("block-cap"), signer, true, false, 2000)) {
            assertThatThrownBy(() -> apply(fixture, bulkMachine(1025), 1, List.of()))
                    .hasRootCauseMessage("observation per-block creation bound exceeded");
            assertThat(fixture.ledger.tipHeight()).isZero();
            assertThat(fixture.ledger.observationReader().activeCount()).isZero();
        }
        try (Fixture fixture = fixture(directory.resolve("app-cap"), signer, true)) {
            apply(fixture, bulkMachine(6), 1, List.of());
            assertThatThrownBy(() -> apply(fixture, bulkMachine(6), 2, List.of()))
                    .hasRootCauseMessage("observation subscription quota exceeded");
            assertThat(fixture.ledger.tipHeight()).isEqualTo(1);
            assertThat(fixture.ledger.observationReader().activeCount("test-app")).isEqualTo(6);
            fixture.ledger.verifyObservationIndexes();
        }
    }

    private static AppStateMachine bulkMachine(int count) {
        return new AppStateMachine() {
            @Override public String id() { return "test-app"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects, AppObservationEmitter observations) {
                for (int index = 0; index < count; index++) observations.watch(ObservationIntent.oneShot(
                        "delivery", "quota", new byte[0], ObservationAnchorType.APP_HEIGHT, 100, 104, 104));
            }
        };
    }

    private static Fixture fixture(Path directory, AppMessageSigner signer) {
        return fixture(directory, signer, false);
    }

    private static Fixture fixture(Path directory, AppMessageSigner signer, boolean recurring) {
        return fixture(directory, signer, recurring, false);
    }

    private static Fixture fixture(Path directory, AppMessageSigner signer,
                                   boolean recurring, boolean slots) {
        return fixture(directory, signer, recurring, slots, 100);
    }

    private static Fixture fixture(Path directory, AppMessageSigner signer,
                                   boolean recurring, boolean slots, int capacity) {
        return fixture(directory, signer, recurring, slots, capacity, StateCommitFaultInjector.NONE);
    }

    private static Fixture fixture(Path directory, AppMessageSigner signer,
                                   boolean recurring, boolean slots, int capacity,
                                   StateCommitFaultInjector faults) {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(MEMBER_SEED)
                .memberKeysHex(Set.of(signer.publicKeyHex()))
                .proposerKeyHex(signer.publicKeyHex())
                .maxBlockMessages(100)
                .stateCommitmentIdentity(TestStateCommitments.MPF)
                .build();
        EffectsSettings effects = EffectsSettings.from(config);
        var consensusProfile = effects.consensusProfile(config);
        ObservationDefinition definition = definition(signer.publicKey(), recurring);
        ObservationProfileV1 profile = profile(definition, recurring, slots, capacity);
        ObservationKernel observations = new ObservationKernel(profile,
                TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(consensusProfile), 0,
                height -> new AppChainMembershipEpoch(0,
                        List.of(signer.publicKeyHex()), 1),
                ObservationKernel.exactValueRegistry(
                        (ignoredDefinition, ignoredRound, report) ->
                                report.evidence().length == 0));
        SystemInputKernel kernel = new SystemInputKernel(effects,
                new ConsensusProfileGuard(consensusProfile),
                new ObservationProfileGuard(profile), observations);
        AppLedgerStore ledger = new AppLedgerStore(directory.resolve("ledger").toString(),
                LoggerFactory.getLogger(ObservationKernelTest.class), TestStateCommitments.MPF, faults);
        return new Fixture(ledger, kernel, definition, profile, consensusProfile);
    }

    private static AppStateMachine machine(AtomicReference<ObservationSubscriptionId> emitted,
                                           AtomicInteger callbacks) {
        return new AppStateMachine() {
            @Override public String id() { return "test-app"; }

            @Override
            public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                              AppEffectEmitter effects) {
            }

            @Override
            public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                              AppEffectEmitter effects, AppObservationEmitter observations) {
                if (context.block().height() == 1) {
                    emitted.set(observations.watch(ObservationIntent.oneShot(
                            "delivery", "order/42", new byte[]{4, 2},
                            ObservationAnchorType.APP_HEIGHT, 2, 4, 4)));
                }
            }

            @Override
            public void onObservationResult(AppBlockExecutionContext context,
                                            ObservationResult result,
                                            AppStateWriter writer,
                                            AppEffectEmitter effects,
                                            AppObservationEmitter observations) {
                callbacks.incrementAndGet();
                writer.put("app/result".getBytes(StandardCharsets.US_ASCII), result.value());
            }
        };
    }

    private static void apply(Fixture fixture, AppStateMachine machine,
                              long height, List<AppMessage> messages) {
        apply(fixture, machine, height, messages, 0);
    }

    private static void apply(Fixture fixture, AppStateMachine machine,
                              long height, List<AppMessage> messages, long slot) {
        byte[] previous = fixture.ledger.tipHash();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN_ID, height,
                previous, slot, slot > 0 ? new byte[32] : new byte[0], height,
                AppBlockCodec.messagesRoot(messages), new byte[32], messages,
                new byte[32], FinalityCert.empty());
        FxBlockApplier.applyAndCommit(fixture.ledger, fixture.kernel, machine, block);
    }

    private static ObservationCertificate certificate(Fixture fixture,
                                                      AppMessageSigner signer,
                                                      ObservationRound round,
                                                      byte[] value) {
        ObservationReport unsigned = new ObservationReport(1,
                TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(fixture.consensusProfile),
                fixture.profile.digest(), fixture.definition.digest(),
                round.subscriptionId(), round.roundNumber(), round.membershipDigest(),
                round.reporterSetDigest(), signer.publicKey(), new byte[]{9}, value,
                new byte[0], new byte[]{1}, 0, 1, new byte[64]);
        ObservationReport report = new ObservationReport(1,
                unsigned.chainGenesisId(), unsigned.chainId(), unsigned.consensusProfileDigest(),
                unsigned.observationProfileDigest(), unsigned.definitionDigest(),
                unsigned.subscriptionId(), unsigned.roundNumber(), unsigned.membershipDigest(),
                unsigned.reporterSetDigest(), unsigned.reporterPublicKey(), unsigned.sourceId(),
                unsigned.value(), unsigned.evidence(), unsigned.sourceVersion(),
                unsigned.freshnessAnchorType(), unsigned.freshnessAnchor(),
                signer.sign(unsigned.signingDigest()));
        byte[] resultId = ObservationHashes.resultId(round.subscriptionId(), round.roundNumber(),
                round.definitionDigest(),
                ObservationResultStatus.VALUE,
                ObservationHashes.digest(value));
        return new ObservationCertificate(1, round.subscriptionId(), round.roundNumber(),
                round.membershipDigest(), round.definitionDigest(), round.policyDigest(),
                round.sourceSetDigest(), List.of(report), value, new byte[0], resultId);
    }

    private static void assertCertificateValid(Fixture fixture, AppMessageSigner signer,
                                               ObservationRound round,
                                               ObservationCertificate certificate) {
        ObservationReport report = certificate.reports().getFirst();
        assertThat(AppMessageSigner.verify(report.signature(), report.signingDigest(),
                report.reporterPublicKey())).as("report signature").isTrue();
        var validation = ObservationCertificateVerifier.validate(
                fixture.definition, round, certificate,
                fixture.profile, TestStateCommitments.MPF.genesisId(), CHAIN_ID,
                AppChainConsensusProfileCommitment.digest(fixture.consensusProfile),
                List.of(signer.publicKey()), (publicKey, digest, signature) ->
                        AppMessageSigner.verify(signature, digest, publicKey),
                (definition, candidateRound, candidateReport) -> true,
                new ExactValueQuorumPolicy());
        assertThat(validation.valid()).as(validation.reason()).isTrue();
    }

    private static ObservationDefinition definition(byte[] reporter) {
        return definition(reporter, false);
    }

    private static ObservationDefinition definition(byte[] reporter, boolean recurring) {
        byte[] reporterSet = recurring ? ObservationHashes.activeMemberRuleDigest()
                : ObservationHashes.reporterSetDigest(List.of(reporter));
        return new ObservationDefinition(1, "delivery", 1, filled(1), filled(2),
                filled(3), filled(4), ObservationReporterMode.ACTIVE_MEMBERS,
                reporterSet, 0, 1, 1, false, "test", filled(5), "identity-v1",
                ObservationSettings.RAW_EXACT_EVIDENCE, ObservationSettings.EXACT_POLICY,
                filled(6), filled(7), "one-source-v1", "source-version-v1",
                "digest-v1", 1, 1024, 1024, 1024, 1, 1);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition) {
        return profile(definition, false);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition, boolean recurring) {
        return profile(definition, recurring, false);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition,
                                               boolean recurring, boolean slots) {
        return profile(definition, recurring, slots, 100);
    }

    private static ObservationProfileV1 profile(ObservationDefinition definition,
                                               boolean recurring, boolean slots, int capacity) {
        return new ObservationProfileV1(1, true, 1, recurring ? 2 : 1, 1, slots ? 2 : 1,
                recurring ? 2 : 1, 1, 1,
                List.of(definition), capacity, capacity == 100 ? 10 : capacity, capacity,
                10, 100, 1, 1,
                4096, 1024, 8192, 10, 16_384, 1, 20, 3);
    }

    private static AppMessage resultMessage(ObservationCertificate certificate, long sequence) {
        return message("~obs/result/v1", certificate.encode(), sequence);
    }

    private static AppMessage message(String topic, byte[] body, long sequence) {
        byte[] sender = new byte[32];
        long expiry = 4_000_000_000L;
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(
                        CHAIN_ID, topic, sender, sequence, expiry, body))
                .chainId(CHAIN_ID).topic(topic).sender(sender).senderSeq(sequence)
                .expiresAt(expiry).body(body).authScheme(0).authProof(new byte[64]).build();
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private record Fixture(AppLedgerStore ledger, SystemInputKernel kernel,
                           ObservationDefinition definition, ObservationProfileV1 profile,
                           AppChainConsensusProfile consensusProfile) implements AutoCloseable {
        @Override public void close() { ledger.close(); }
    }
}
