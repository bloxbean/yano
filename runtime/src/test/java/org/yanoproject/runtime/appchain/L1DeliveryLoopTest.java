package org.yanoproject.runtime.appchain;

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
        L1DeliveryLoop.Reconciliation decision =
                new L1DeliveryLoop.Reconciliation(batch -> { }, null, false, OptionalLong.empty());
        int reconcileCalls;
        int reloads;

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
            return decision;
        }

        @Override
        public void reconciled() {
            reloads++;
        }

        @Override
        public List<L1PhaseResult> rollbackTo(L1Point target) {
            rollbacks.add(target);
            L1PhaseResult result = nextRollback.isEmpty() ? L1PhaseResult.DURABLE : nextRollback.poll();
            if (result.succeeded()) {
                events.add("rollback " + target.blockNumber());
            }
            return List.of(result);
        }
    }
}
