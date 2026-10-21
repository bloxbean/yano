package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.yanoproject.api.events.BlockAppliedEvent;
import org.yanoproject.runtime.chain.BlockBodyRetentionRegistry;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Random;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The delivery loop against a real in-memory chain state (app-layer ADR-038). Every assertion compares what the
 * host saw with the canonical chain read independently from the fixture, never with the loop's own state.
 */
class L1DeliveryLoopTest {
    @TempDir
    Path dir;

    private final Logger log = mock(Logger.class);
    private final L1TestChain l1 = new L1TestChain();
    private final RecordingHost host = new RecordingHost();
    private final BlockBodyRetentionRegistry registry = new BlockBodyRetentionRegistry();
    private BlockBodyRetentionRegistry.Registration retention;
    private AppLedgerStore ledger;

    @BeforeEach
    void setUp() {
        ledger = new AppLedgerStore(dir.resolve("ledger").toString(), log);
        retention = registry.register(OptionalLong.of(0));
    }

    @AfterEach
    void tearDown() {
        ledger.close();
    }

    private L1DeliveryLoop loop() {
        return new L1DeliveryLoop("test", 1, l1.reader(), ledger, host, retention, log);
    }

    /** Blocks 0..4 exist before the chain starts, so they form the baseline history. */
    private L1DeliveryLoop startedAtBlock4() {
        l1.append(10, 20, 30, 40, 50);
        L1DeliveryLoop loop = loop();
        loop.pass();
        assertThat(loop.snapshot().cursor()).isEqualTo(l1.point(4));
        return loop;
    }

    @Test
    void deliversEveryBlockAfterTheBaselineInCanonicalOrder() {
        L1DeliveryLoop loop = startedAtBlock4();
        assertThat(loop.deliveryHealthy()).isTrue();
        assertThat(loop.stablePoint()).as("no delivered points yet").isNull();

        l1.append(60, 70, 80);
        loop.pass();

        assertThat(host.applied).containsExactly(l1.point(5), l1.point(6), l1.point(7));
        assertThat(loop.snapshot().record().window()).containsExactly(l1.point(5), l1.point(6), l1.point(7));
        assertThat(loop.stablePoint().slot()).as("one block below the newest delivered point").isEqualTo(70);
        assertThat(registry.oldestRequiredBlockNumber()).hasValue(8);
    }

    @Test
    void lostEventsCostNothingBecauseEachPassReadsChainState() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60);
        l1.append(70);
        // No wake-up was ever sent; a single poll-driven pass still delivers both blocks (I6).
        loop.pass();
        assertThat(host.applied).containsExactly(l1.point(5), l1.point(6));
    }

    @Test
    void rollbackRunsBeforeAnyBlockOfTheNewBranch() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70, 80);
        loop.pass();

        l1.fork(5, 75, 85);
        loop.pass();

        assertThat(host.rollbacks).containsExactly(l1.point(5));
        assertThat(host.events).containsSubsequence("apply 7", "rollback 5", "apply 6", "apply 7");
        assertThat(loop.snapshot().record().window()).containsExactly(l1.point(5), l1.point(6), l1.point(7));
    }

    @Test
    void partlyAppliedBlockIsRolledBackBeforeAReplacementAtADifferentSlot() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60);
        loop.pass();
        host.nextApply.add(L1PhaseResult.retryable("STORAGE"));
        l1.append(70);
        loop.pass();
        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.RETRYING_BLOCK);

        l1.fork(5, 65);
        loop.pass();

        assertThat(host.rollbacks).containsExactly(l1.point(5));
        assertThat(host.applied).containsExactly(l1.point(5), l1.point(6));
        assertThat(host.applied.getLast().slot()).isEqualTo(65);
    }

    @Test
    void partlyAppliedBlockIsRolledBackBeforeAReplacementAtTheSameSlot() {
        L1DeliveryLoop loop = startedAtBlock4();
        host.nextApply.add(L1PhaseResult.retryable("STORAGE"));
        l1.append(60);
        loop.pass();

        // Block 5 is replaced at the same slot 60, on a branch that also replaces block 4.
        l1.fork(3, 45, 60);
        loop.pass();

        assertThat(host.rollbacks).containsExactly(l1.point(3));
        assertThat(host.applied).containsExactly(l1.point(4), l1.point(5));
        assertThat(host.applied.getLast().slot()).isEqualTo(60);
    }

    @Test
    void forkBetweenTheIntentAndTheBodyReadIsNeverSubstituted() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(50 + 10);
        l1.beforeBodyRead = once(() -> l1.fork(4, 55));
        loop.pass();
        assertThat(host.attempts).as("the new block never runs under the old intent").isEmpty();

        loop.pass();
        assertThat(host.rollbacks).containsExactly(l1.point(4));
        assertThat(host.applied).containsExactly(l1.point(5));
        assertThat(host.applied.getFirst().slot()).isEqualTo(55);
    }

    /** A fork that lands while the next reference is read never puts a new-branch block on the dead cursor (I2). */
    @Test
    void forkDuringTheNextReferenceReadIsRolledBackNotStackedOnTheDeadCursor() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60);
        loop.pass();
        L1Point dead = l1.point(5);
        l1.append(70);
        boolean[] forked = new boolean[1];
        l1.beforeReferenceRead = number -> {
            if (number == 6 && !forked[0]) {
                forked[0] = true;
                l1.fork(4, 55, 65);
            }
        };
        loop.pass();
        assertThat(host.applied).as("block 6' is never applied on dead block 5").containsExactly(dead);

        loop.pass();
        assertThat(host.rollbacks).containsExactly(l1.point(4));
        assertThat(host.applied).containsExactly(dead, l1.point(5), l1.point(6));
        assertThat(loop.snapshot().record().window()).containsExactly(l1.point(5), l1.point(6));
    }

    /** D9a: an ordinary APPLY in progress keeps the fence open on the committed window; a retry closes it. */
    @Test
    void fenceStaysOpenDuringAnOrdinaryApplyAndClosesOnRetry() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70);
        loop.pass();
        l1.append(80);
        List<Object> seenDuringApply = new ArrayList<>();
        host.duringApply = once(event -> {
            AppChainEngine.L1Ref stable = loop.stablePoint();
            seenDuringApply.add(loop.deliveryHealthy());
            seenDuringApply.add(stable != null ? stable.slot() : null);
        });
        loop.pass();
        assertThat(seenDuringApply).containsExactly(true, 60L);
        assertThat(loop.healthyCursorSlot()).isEqualTo(80);

        l1.append(90);
        host.nextApply.add(L1PhaseResult.retryable("STORAGE"));
        loop.pass();
        assertThat(loop.deliveryHealthy()).isFalse();
        assertThat(loop.stablePoint()).isNull();
        assertThat(loop.healthyCursorSlot()).as("no L1 fact may be recorded outside the loop").isEqualTo(-1);
    }

    /** I10: delivery stops at the body tip even when canonical references exist above it. */
    @Test
    void deliveryStopsAtTheBodyTip() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70);
        l1.bodyTipBlock = 5L;
        loop.pass();
        assertThat(host.applied).containsExactly(l1.point(5));

        l1.bodyTipBlock = null;
        loop.pass();
        assertThat(host.applied).containsExactly(l1.point(5), l1.point(6));
    }

    /** D4: a failing intent backs off; a rollback wake skips the backoff once, and a success resets it. */
    @Test
    void retriesBackOffAndARollbackWakeSkipsTheBackoff() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60);
        host.nextApply.add(L1PhaseResult.retryable("STORAGE"));
        host.nextApply.add(L1PhaseResult.retryable("STORAGE"));
        loop.runPass();
        loop.runPass();
        assertThat(host.attempts).as("the second pass is inside the backoff").hasSize(1);

        loop.wakeForRollback(); // not started: only the bypass flag is set
        loop.runPass();
        assertThat(host.attempts).hasSize(2);
        loop.runPass();
        assertThat(host.attempts).as("the bypass is used once").hasSize(2);

        assertThat(loop.requestRebaseline()).isTrue();
        loop.runPass(); // reconciles and re-baselines over block 5, which resets the backoff
        l1.append(70);
        loop.runPass();
        assertThat(host.attempts).hasSize(3);
        assertThat(host.applied).containsExactly(l1.point(6));
    }

    /** I13 interleavings: every fenced reader is closed during a pending rollback, a reconciliation and a stop. */
    @Test
    void fencedReadersAreClosedDuringARollbackAReconciliationAndATerminalState() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70, 80);
        loop.pass();
        L1Point stable = l1.point(6);
        assertThat(fencedReaders(loop, stable)).as("open").containsExactly(true, true, AppChainEngine.L1RefVerdict.OK, true);

        List<List<Object>> closed = new ArrayList<>();
        host.duringRollback = once(() -> closed.add(fencedReaders(loop, stable)));
        l1.fork(6, 85);
        loop.pass();
        host.duringReconcile = once(() -> closed.add(fencedReaders(loop, stable)));
        assertThat(loop.requestRebaseline()).isTrue();
        loop.pass();
        l1.append(90);
        l1.earliestRetained = l1.tipNumber() + 1;
        loop.pass();
        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.L1_BODY_UNAVAILABLE);
        closed.add(fencedReaders(loop, stable));

        assertThat(closed).hasSize(3).allSatisfy(readers ->
                assertThat(readers).containsExactly(false, false, AppChainEngine.L1RefVerdict.UNKNOWN, false));
    }

    private static List<Object> fencedReaders(L1DeliveryLoop loop, L1Point point) {
        return List.of(loop.deliveryHealthy(), loop.stablePoint() != null,
                loop.checkL1Ref(point.slot(), point.blockHash()), loop.healthyCursorSlot() >= 0);
    }

    /** Negative case (ADR verification): Byron blocks reach the phases unparsed, and a fork rolls back onto one. */
    @Test
    void byronBlocksAreDeliveredUnparsedAndCanBeRollbackTargets() {
        L1DeliveryLoop loop = loop();
        loop.pass();
        l1.appendByron(10, 20);
        l1.append(30);
        List<Boolean> unparsedByron = new ArrayList<>();
        host.duringApply = event -> unparsedByron.add(event.era() == Era.Byron && event.block() == null);
        loop.pass();
        assertThat(host.applied).containsExactly(l1.point(0), l1.point(1), l1.point(2));
        assertThat(unparsedByron).containsExactly(true, true, false);

        l1.fork(0, 25);
        loop.pass();
        assertThat(host.rollbacks).containsExactly(l1.point(0));
        assertThat(loop.snapshot().record().window()).containsExactly(l1.point(0), l1.point(1));
    }

    /** D8b rule 6: a record older than the chain index cannot be judged, so the evidence is unavailable. */
    @Test
    void aDeadRecordOlderThanTheChainIndexMakesTheEvidenceUnavailable() {
        assertThat(reconcileLegacyRecordAtSlot(50)).isEqualTo(L1DeliveryLoop.State.L1_EVIDENCE_UNAVAILABLE);
    }

    @Test
    void aDeadRecordInsideTheChainIndexKeepsTheQuarantine() {
        assertThat(reconcileLegacyRecordAtSlot(150)).isEqualTo(L1DeliveryLoop.State.QUARANTINED);
    }

    /** A legacy ledger whose one dead record sits at {@code slot}; the chain index starts at slot 100. */
    private L1DeliveryLoop.State reconcileLegacyRecordAtSlot(long slot) {
        l1.append(100, 110, 120, 130, 140);
        l1.earliestIndexedSlot = 100L;
        host.legacyState = true;
        host.probes.add(new L1Point(9, slot, new byte[32]));
        host.decision = new L1DeliveryLoop.Reconciliation(batch -> { },
                "DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION", false, OptionalLong.empty());
        L1DeliveryLoop loop = loop();
        loop.pass();
        return loop.snapshot().state();
    }

    /** PR #167 review (I4): a node bootstrapped with ten indexed blocks starts at once and misses no later block. */
    @Test
    void aBootstrappedNodeStartsAtItsIndexedHorizonAndDeliversEveryLaterBlock() {
        l1.append(LongStream.rangeClosed(1, 100).map(i -> i * 10).toArray());
        l1.earliestIndexedNumber = 90;
        l1.earliestIndexedSlot = 910L;
        L1DeliveryLoop loop = loop();
        loop.pass();
        assertThat(loop.snapshot().record().baseline()).first().isEqualTo(l1.point(90));
        assertThat(loop.snapshot().cursor()).isEqualTo(l1.point(99));

        for (long slot = 1010; slot <= 1550; slot += 10) {
            l1.append(slot);
            loop.pass();
        }
        assertThat(host.applied).containsExactlyElementsOf(
                LongStream.rangeClosed(100, 154).mapToObj(l1::point).toList());
    }

    @Test
    void aGapInsideTheIndexedHistoryStillWaits() {
        l1.append(LongStream.rangeClosed(1, 100).map(i -> i * 10).toArray());
        l1.earliestIndexedNumber = 90;
        l1.earliestIndexedSlot = 500L; // the index claims older history, so block 89 missing is a gap
        L1DeliveryLoop loop = loop();
        loop.pass();
        assertThat(loop.snapshot().record()).isNull();
    }

    /** PR #167 review (I1): a cache reload failing after a reconciliation commit keeps the committed baseline. */
    @Test
    void aReloadFailureAfterAReconciliationCommitKeepsTheCommittedBaseline() {
        l1.append(10, 20, 30, 40, 50);
        host.legacyState = true;
        host.duringReload = once(() -> {
            throw new IllegalStateException("transient read failure");
        });
        L1DeliveryLoop loop = loop();
        loop.runPass();
        assertThat(L1DeliveryRecord.decode(ledger.metaBytes(L1DeliveryRecord.META_KEY)).cursor())
                .isEqualTo(l1.point(4));
        assertThat(loop.deliveryHealthy()).as("the fence stays closed until the reload succeeds").isFalse();

        l1.append(60);
        loop.pass();
        assertThat(host.reconcileCalls).as("the reload is retried, not the reconciliation").isEqualTo(1);
        assertThat(host.reloads).isEqualTo(2);
        assertThat(host.applied).containsExactly(l1.point(5));
    }

    @Test
    void forkBetweenThePhasesAndTheCommitIsRolledBack() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60);
        host.duringApply = once(event -> l1.fork(4, 55));
        loop.pass();
        assertThat(loop.snapshot().record().window()).as("the dead block is never committed").isEmpty();

        loop.pass();
        assertThat(host.rollbacks).containsExactly(l1.point(4));
        assertThat(loop.snapshot().record().window()).containsExactly(l1.point(5));
        assertThat(l1.point(5).slot()).isEqualTo(55);
    }

    @Test
    void failedRollbackRetriesAcrossARestartWithoutShrinkingOrDelivering() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70, 80);
        loop.pass();
        List<L1Point> before = loop.snapshot().record().window();
        host.nextRollback.add(L1PhaseResult.retryable("STORAGE"));
        l1.fork(5, 75);
        loop.pass();

        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.RETRYING_ROLLBACK);
        assertThat(loop.snapshot().record().window()).isEqualTo(before);
        assertThat(loop.deliveryHealthy()).isFalse();
        assertThat(host.applied).hasSize(3);

        L1DeliveryLoop restarted = loop();
        restarted.pass();
        assertThat(host.rollbacks).containsExactly(l1.point(5), l1.point(5));
        assertThat(restarted.snapshot().record().window()).containsExactly(l1.point(5), l1.point(6));
    }

    @Test
    void restartRedeliversThePendingBlockExactlyAsRecorded() {
        L1DeliveryLoop loop = startedAtBlock4();
        host.nextApply.add(L1PhaseResult.retryable("CRASH_BEFORE_COMMIT"));
        l1.append(60);
        loop.pass();

        L1DeliveryLoop restarted = loop();
        restarted.pass();
        assertThat(host.attempts).containsExactly(l1.point(5), l1.point(5));
        assertThat(restarted.snapshot().record().window()).containsExactly(l1.point(5));
        assertThat(host.rollbacks).isEmpty();
    }

    @Test
    void shallowForkOfTheBaselineMovesTheCursorAndResumesOnce() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.fork(3, 45);
        loop.pass();
        loop.pass();

        assertThat(host.rollbacks).as("one rollback, not a repeating one (I17)").containsExactly(l1.point(3));
        assertThat(host.applied).containsExactly(l1.point(4));
        assertThat(host.applied.getFirst().slot()).isEqualTo(45);
    }

    @Test
    void forkOfGenesisRollsBackToOrigin() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.forkFromOrigin(5, 15);
        loop.pass();

        assertThat(host.rollbacks).containsExactly(L1Point.ORIGIN);
        assertThat(host.applied).containsExactly(l1.point(0), l1.point(1));
        assertThat(registry.oldestRequiredBlockNumber()).hasValue(2);
    }

    @Test
    void divergenceBelowEveryRecordedPointFailsClosedAndSurvivesRestart() {
        l1.append(LongStream.rangeClosed(1, 70).map(i -> i * 10).toArray());
        L1DeliveryLoop loop = loop();
        loop.pass();
        assertThat(loop.snapshot().record().baseline()).doesNotContain(L1Point.ORIGIN);

        l1.fork(2, 25, 35);
        loop.pass();
        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.L1_DIVERGENCE_BEYOND_WINDOW);

        L1DeliveryLoop restarted = loop();
        restarted.pass();
        assertThat(restarted.snapshot().state()).isEqualTo(L1DeliveryLoop.State.L1_DIVERGENCE_BEYOND_WINDOW);
        assertThat(host.rollbacks).isEmpty();
    }

    @Test
    void freshnessFenceClosesTheMomentChainStateRollsBack() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70, 80);
        loop.pass();
        assertThat(loop.stablePoint()).isNotNull();

        // Every event dropped and the loop not yet run: readers still see the rollback (D9a check 3).
        l1.fork(5, 75);
        assertThat(loop.deliveryHealthy()).isFalse();
        assertThat(loop.stablePoint()).isNull();
        assertThat(loop.checkL1Ref(70, l1.point(5).blockHash())).isEqualTo(AppChainEngine.L1RefVerdict.UNKNOWN);
    }

    @Test
    void freshnessFenceChecksAnOrphanedBaselineWhenTheWindowIsEmpty() {
        l1.append(LongStream.rangeClosed(1, 70).map(i -> i * 10).toArray());
        L1DeliveryLoop loop = loop();
        loop.pass();
        assertThat(loop.snapshot().record().window()).isEmpty();
        assertThat(loop.deliveryHealthy()).isTrue();

        l1.fork(68, 695);
        assertThat(loop.deliveryHealthy()).as("an empty window does not skip the check (r5)").isFalse();
    }

    @Test
    void quarantineIsTerminalAndSurvivesRestart() {
        L1DeliveryLoop loop = startedAtBlock4();
        host.nextApply.add(L1PhaseResult.quarantined("DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION"));
        l1.append(60);
        loop.pass();
        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.QUARANTINED);

        L1DeliveryLoop restarted = loop();
        restarted.pass();
        assertThat(restarted.snapshot().state()).isEqualTo(L1DeliveryLoop.State.QUARANTINED);
        assertThat(host.attempts).hasSize(1);
    }

    @Test
    void prunedBodyFailsClosedInsteadOfSkipping() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70);
        l1.earliestRetained = 6;
        loop.pass();
        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.L1_BODY_UNAVAILABLE);
        assertThat(host.attempts).isEmpty();
    }

    @Test
    void unsupportedMutationSequenceFailsClosedWithoutARecord() {
        l1.append(10, 20);
        l1.sequenceSupported = false;
        L1DeliveryLoop loop = loop();
        loop.pass();
        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.L1_EVIDENCE_UNAVAILABLE);
        assertThat(ledger.metaBytes(L1DeliveryRecord.META_KEY)).isNull();
        assertThat(loop.deliveryHealthy()).isFalse();
    }

    @Test
    void retentionProtectsEveryUndeliveredBodyIncludingDuringARollback() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70, 80);
        loop.pass();
        assertThat(registry.oldestRequiredBlockNumber()).hasValue(8);

        host.nextRollback.add(L1PhaseResult.retryable("STORAGE"));
        l1.fork(5, 75);
        loop.pass();
        assertThat(registry.oldestRequiredBlockNumber()).as("lowered to the target before the intent").hasValue(6);
    }

    @Test
    void checkL1RefJudgesAgainstTheDeliveredWindow() {
        L1DeliveryLoop loop = startedAtBlock4();
        l1.append(60, 70, 80);
        loop.pass();

        assertThat(loop.checkL1Ref(80, l1.point(7).blockHash())).as("not deep enough yet")
                .isEqualTo(AppChainEngine.L1RefVerdict.AHEAD);
        assertThat(loop.checkL1Ref(70, l1.point(6).blockHash())).isEqualTo(AppChainEngine.L1RefVerdict.OK);
        assertThat(loop.checkL1Ref(70, l1.point(5).blockHash())).isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);
        assertThat(loop.checkL1Ref(65, l1.point(5).blockHash())).isEqualTo(AppChainEngine.L1RefVerdict.MISMATCH);
        assertThat(loop.checkL1Ref(90, l1.point(7).blockHash())).isEqualTo(AppChainEngine.L1RefVerdict.AHEAD);
        assertThat(loop.checkL1Ref(50, l1.point(4).blockHash())).isEqualTo(AppChainEngine.L1RefVerdict.UNKNOWN);
    }

    @Test
    void upgradeReconcilesBeforeAnyDeliveryAndBindsTheBaselineToTheSameChain() {
        l1.append(10, 20, 30, 40, 50);
        host.legacyState = true;
        L1DeliveryLoop loop = loop();
        loop.pass();

        assertThat(host.reconcileCalls).isEqualTo(1);
        assertThat(host.reloads).isEqualTo(1);
        assertThat(loop.snapshot().record().phase()).isEqualTo(L1DeliveryRecord.Phase.RECONCILED);
        assertThat(loop.snapshot().cursor()).isEqualTo(l1.point(4));
        assertThat(loop.snapshot().record().window()).as("the baseline is never delivered").isEmpty();
        l1.append(60);
        loop.pass();
        assertThat(host.applied).containsExactly(l1.point(5));
    }

    @Test
    void reconciliationPassOverlappingARollbackIsDiscardedAndRerun() {
        l1.append(10, 20, 30, 40, 50);
        host.legacyState = true;
        host.duringReconcile = once(() -> l1.fork(3, 45));
        L1DeliveryLoop loop = loop();
        loop.pass();
        assertThat(loop.snapshot().record().phase()).as("the mutation sequence moved: nothing committed")
                .isEqualTo(L1DeliveryRecord.Phase.RECONCILING);

        loop.pass();
        assertThat(host.reconcileCalls).isEqualTo(2);
        assertThat(loop.snapshot().record().phase()).isEqualTo(L1DeliveryRecord.Phase.RECONCILED);
        assertThat(loop.snapshot().cursor()).as("the baseline comes from the chain the decisions saw")
                .isEqualTo(l1.point(4));
        assertThat(l1.point(4).slot()).isEqualTo(45);
    }

    @Test
    void crashBeforeTheReconciliationCommitRerunsTheProcedure() {
        l1.append(10, 20, 30);
        host.legacyState = true;
        host.duringReconcile = once(() -> l1.fork(1, 25));
        loop().pass();

        L1DeliveryLoop restarted = loop();
        restarted.pass();
        assertThat(host.reconcileCalls).isEqualTo(2);
        assertThat(restarted.snapshot().record().phase()).isEqualTo(L1DeliveryRecord.Phase.RECONCILED);
    }

    @Test
    void reconciliationQuarantineCommitsItsMarkersAndIsTerminal() {
        l1.append(10, 20);
        host.legacyState = true;
        host.decision = new L1DeliveryLoop.Reconciliation(
                batch -> ledger.stageMetaBytes(batch, "test_marker", new byte[]{1}),
                "DEEP_L1_ROLLBACK_BELOW_FINALIZED_OBSERVATION", false, OptionalLong.empty());
        L1DeliveryLoop loop = loop();
        loop.pass();

        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.QUARANTINED);
        assertThat(ledger.metaBytes("test_marker")).containsExactly(1);
        assertThat(loop.requestRebaseline()).as("re-baseline never clears a quarantine").isFalse();
    }

    @Test
    void missingEvidenceFailsClosedWithoutApplyingAnything() {
        l1.append(10, 20);
        host.legacyState = true;
        host.decision = new L1DeliveryLoop.Reconciliation(
                batch -> ledger.stageMetaBytes(batch, "test_marker", new byte[]{1}), null, true,
                OptionalLong.empty());
        L1DeliveryLoop loop = loop();
        loop.pass();

        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.L1_EVIDENCE_UNAVAILABLE);
        assertThat(ledger.metaBytes("test_marker")).isNull();
    }

    @Test
    void failedCallbackSlotMovesTheBaselineSoTheFailedBlockIsDelivered() {
        l1.append(10, 20, 30, 40, 50);
        host.legacyState = true;
        host.decision = new L1DeliveryLoop.Reconciliation(batch -> { }, null, false, OptionalLong.of(2));
        L1DeliveryLoop loop = loop();
        loop.pass();
        loop.pass();

        assertThat(host.applied).containsExactly(l1.point(3), l1.point(4));
    }

    @Test
    void operatorRebaselineLeavesADivergenceStateAndReconciles() {
        l1.append(LongStream.rangeClosed(1, 70).map(i -> i * 10).toArray());
        L1DeliveryLoop loop = loop();
        loop.pass();
        l1.fork(2, 25, 35);
        loop.pass();
        assertThat(loop.snapshot().state()).isEqualTo(L1DeliveryLoop.State.L1_DIVERGENCE_BEYOND_WINDOW);

        assertThat(loop.requestRebaseline()).isTrue();
        loop.pass();
        assertThat(host.reconcileCalls).isEqualTo(1);
        assertThat(loop.snapshot().record().phase()).isEqualTo(L1DeliveryRecord.Phase.RECONCILED);
        assertThat(loop.snapshot().cursor()).isEqualTo(l1.point(l1.tipNumber()));
        assertThat(loop.snapshot().record().terminal()).isNull();
    }

    /**
     * Differential model test (ADR verification strategy): random schedules of appends, forks of random depth, phase
     * failures and restarts. After each schedule, the host's surviving derived state must equal the canonical chain
     * read independently from the fixture: no dead-fork effect survives and no canonical block is missing.
     */
    @Test
    void randomSchedulesAlwaysConvergeOnTheCanonicalChain() {
        for (int seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            L1TestChain chain = new L1TestChain();
            DerivedStateHost derived = new DerivedStateHost();
            try (AppLedgerStore seedLedger = new AppLedgerStore(dir.resolve("seed-" + seed).toString(), log)) {
                long[] slot = {0};
                for (int i = 0; i < 5; i++) {
                    chain.append(slot[0] += 10);
                }
                L1DeliveryLoop loop = seededLoop(chain, seedLedger, derived);
                settle(loop);
                for (int step = 0; step < 40; step++) {
                    int op = random.nextInt(10);
                    if (op < 5) {
                        for (int n = 1 + random.nextInt(3); n > 0; n--) {
                            chain.append(slot[0] += 10);
                        }
                    } else if (op < 7 && chain.tipNumber() > 0) {
                        long keep = Math.max(0, chain.tipNumber() - 1 - random.nextInt(4));
                        if (random.nextBoolean()) {
                            chain.forkAtSameSlot(keep); // the replaced block keeps its slot
                        } else {
                            chain.fork(keep, slot[0] += 7);
                        }
                        for (int b = random.nextInt(3); b > 0; b--) {
                            chain.append(slot[0] += 7);
                        }
                    } else if (op < 8 && random.nextBoolean()) {
                        boolean[] fired = new boolean[1];
                        seedLedger.injectMetaWriteFault(() -> {
                            if (!fired[0]) {
                                fired[0] = true;
                                throw new IllegalStateException("disk unavailable"); // the next record write
                            }
                        });
                    } else if (op < 8) {
                        derived.nextApply.add(L1PhaseResult.retryable("INJECTED"));
                    } else if (op < 9) {
                        derived.nextRollback.add(L1PhaseResult.retryable("INJECTED"));
                    } else {
                        loop = seededLoop(chain, seedLedger, derived); // restart from the durable record
                    }
                    try {
                        loop.pass();
                    } catch (IllegalStateException storageFailure) {
                        // A failed record write escapes the pass; the scheduled runner contains it and retries.
                    }
                }
                derived.nextApply.clear();
                derived.nextRollback.clear();
                seedLedger.injectMetaWriteFault(null);
                settle(loop);

                assertThat(loop.snapshot().cursor()).as("seed %d: caught up", seed)
                        .isEqualTo(chain.point(chain.tipNumber()));
                List<L1Point> canonical = new ArrayList<>();
                for (L1Point point : derived.state) {
                    canonical.add(chain.point(point.blockNumber()));
                }
                assertThat(derived.state).as("seed %d: every surviving effect is canonical", seed)
                        .isEqualTo(canonical);
                assertThat(derived.state.getLast()).as("seed %d: nothing canonical is missing", seed)
                        .isEqualTo(chain.point(chain.tipNumber()));
                for (int index = 1; index < derived.state.size(); index++) {
                    assertThat(derived.state.get(index).blockNumber())
                            .isEqualTo(derived.state.get(index - 1).blockNumber() + 1);
                }
            }
        }
    }

    /**
     * Crash points (ADR verification): a process crash before every record write (intents and commits) and before
     * and after every apply and rollback effect, each followed by a restart from the durable record, still ends with
     * the derived state equal to the canonical chain (I1, I3, I5, I11, I12).
     */
    @Test
    void everyCrashPointRecoversToTheCanonicalChain() {
        int crashPoints = 0;
        for (int crashAt = 1; ; crashAt++) {
            CrashSchedule crashes = new CrashSchedule(crashAt);
            L1TestChain chain = new L1TestChain();
            DerivedStateHost derived = new DerivedStateHost();
            derived.crashPoint = crashes::point;
            try (AppLedgerStore crashLedger = new AppLedgerStore(dir.resolve("crash-" + crashAt).toString(), log)) {
                crashLedger.injectMetaWriteFault(crashes::point);
                chain.append(10, 20, 30, 40, 50);
                L1DeliveryLoop loop = seededLoop(chain, crashLedger, derived);
                List<Runnable> steps = List.of(() -> { }, () -> chain.append(60, 70), () -> chain.fork(5, 75),
                        () -> chain.append(85), () -> chain.forkAtSameSlot(4, 95));
                for (Runnable step : steps) {
                    step.run();
                    while (true) {
                        try {
                            settle(loop);
                            break;
                        } catch (SimulatedCrash crash) {
                            loop = seededLoop(chain, crashLedger, derived); // restart from the durable record
                        }
                    }
                }
                assertThat(loop.snapshot().cursor()).as("crash point %d: caught up", crashAt)
                        .isEqualTo(chain.point(chain.tipNumber()));
                assertThat(derived.state).as("crash point %d: exactly the canonical blocks", crashAt)
                        .containsExactly(chain.point(5), chain.point(6));
            }
            if (!crashes.fired) {
                break;
            }
            crashPoints++;
        }
        assertThat(crashPoints).as("the scenario passes many crash points").isGreaterThan(20);
    }

    /** A one-shot process crash at the n-th crash point reached. */
    private static final class CrashSchedule {
        private final int crashAt;
        private int reached;
        private boolean fired;

        private CrashSchedule(int crashAt) {
            this.crashAt = crashAt;
        }

        void point() {
            if (++reached == crashAt) {
                fired = true;
                throw new SimulatedCrash();
            }
        }
    }

    /** Escapes the pass like a process death: it is neither a phase outcome nor a contained failure. */
    private static final class SimulatedCrash extends Error {
    }

    private L1DeliveryLoop seededLoop(L1TestChain chain, AppLedgerStore seedLedger, DerivedStateHost derived) {
        return new L1DeliveryLoop("model", 1, chain.reader(), seedLedger, derived, null, log);
    }

    private static void settle(L1DeliveryLoop loop) {
        for (int pass = 0; pass < 20; pass++) {
            loop.pass();
            L1DeliveryLoop.Snapshot snapshot = loop.snapshot();
            if (snapshot.state() == L1DeliveryLoop.State.RUNNING && snapshot.record().pending() == null) {
                return;
            }
        }
    }

    /** A host whose effects are a list of applied points, truncated by slot on rollback like the real phases. */
    private static final class DerivedStateHost implements L1DeliveryLoop.Host {
        final List<L1Point> state = new ArrayList<>();
        final Deque<L1PhaseResult> nextApply = new ArrayDeque<>();
        final Deque<L1PhaseResult> nextRollback = new ArrayDeque<>();
        Runnable crashPoint = () -> { };

        @Override
        public List<L1PhaseResult> applyBlock(BlockAppliedEvent event) {
            crashPoint.run();
            L1PhaseResult result = nextApply.isEmpty() ? L1PhaseResult.DURABLE : nextApply.poll();
            L1Point point = new L1Point(event.blockNumber(), event.slot(), HexUtil.decodeHexString(event.blockHash()));
            // Effects land even when the attempt fails, as a partly applied block's would (F1).
            if (!state.contains(point)) {
                state.add(point);
            }
            crashPoint.run();
            return List.of(result);
        }

        @Override
        public List<L1PhaseResult> rollbackTo(L1Point target) {
            crashPoint.run();
            state.removeIf(point -> point.slot() > target.slot());
            crashPoint.run();
            return List.of(nextRollback.isEmpty() ? L1PhaseResult.DURABLE : nextRollback.poll());
        }

        @Override
        public boolean hasL1DerivedState() {
            return false;
        }

        @Override
        public L1DeliveryLoop.Reconciliation reconcile(BiPredicate<Long, byte[]> canonicalAtSlot) {
            throw new AssertionError("not an upgrade");
        }

        @Override
        public void reconciled() {
        }
    }

    private static Runnable once(Runnable action) {
        boolean[] done = new boolean[1];
        return () -> {
            if (!done[0]) {
                done[0] = true;
                action.run();
            }
        };
    }

    private static <T> Consumer<T> once(Consumer<T> action) {
        boolean[] done = new boolean[1];
        return value -> {
            if (!done[0]) {
                done[0] = true;
                action.accept(value);
            }
        };
    }

    private static final class RecordingHost implements L1DeliveryLoop.Host {
        final List<L1Point> attempts = new ArrayList<>();
        final List<L1Point> applied = new ArrayList<>();
        final List<L1Point> rollbacks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final Deque<L1PhaseResult> nextApply = new ArrayDeque<>();
        final Deque<L1PhaseResult> nextRollback = new ArrayDeque<>();
        Consumer<BlockAppliedEvent> duringApply = event -> { };
        boolean legacyState;
        Runnable duringReconcile = () -> { };
        Runnable duringRollback = () -> { };
        /** Points the reconciliation judges through the loop's predicate, as the real phases' records would be. */
        final List<L1Point> probes = new ArrayList<>();
        L1DeliveryLoop.Reconciliation decision =
                new L1DeliveryLoop.Reconciliation(batch -> { }, null, false, OptionalLong.empty());
        int reconcileCalls;
        int reloads;
        Runnable duringReload = () -> { };

        @Override
        public List<L1PhaseResult> applyBlock(BlockAppliedEvent event) {
            L1Point point = new L1Point(event.blockNumber(), event.slot(),
                    HexUtil.decodeHexString(event.blockHash()));
            attempts.add(point);
            duringApply.accept(event);
            L1PhaseResult result = nextApply.isEmpty() ? L1PhaseResult.DURABLE : nextApply.poll();
            if (result.succeeded()) {
                applied.add(point);
                events.add("apply " + event.blockNumber());
            }
            return List.of(result);
        }

        @Override
        public boolean hasL1DerivedState() {
            return legacyState;
        }

        @Override
        public L1DeliveryLoop.Reconciliation reconcile(BiPredicate<Long, byte[]> canonicalAtSlot) {
            reconcileCalls++;
            duringReconcile.run();
            probes.forEach(point -> canonicalAtSlot.test(point.slot(), point.blockHash()));
            return decision;
        }

        @Override
        public void reconciled() {
            reloads++;
            duringReload.run();
        }

        @Override
        public List<L1PhaseResult> rollbackTo(L1Point target) {
            rollbacks.add(target);
            duringRollback.run();
            L1PhaseResult result = nextRollback.isEmpty() ? L1PhaseResult.DURABLE : nextRollback.poll();
            if (result.succeeded()) {
                events.add("rollback " + target.blockNumber());
            }
            return List.of(result);
        }
    }
}
