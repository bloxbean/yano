package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batching decides how many Parquet files the archive ends up with, so every bound needs a
 * test that shows which one fired. The first preprod sync produced 67,603 files at 56 KB
 * because the drain loop committed whatever was eligible immediately; these assert the
 * replacement behaves per regime and never exceeds a safety ceiling.
 */
class ProjectionBatchAccumulatorTest {

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture");
    private static final Instant T0 = Instant.EPOCH;

    private static ProjectionEnvelope envelope(long block, long rows, int payloadBytes) {
        var section = new ProjectionSection(ProjectionSectionType.TRANSACTION,
                ProjectionSectionType.TRANSACTION.version(), List.of(new byte[payloadBytes]), rows);
        var header = new ProjectionEnvelopeHeader(NETWORK, ProjectionBlockKind.SHELLEY_PLUS, block,
                new byte[]{(byte) block}, new byte[]{(byte) (block - 1)}, block * 20, 1, 1L, 1,
                List.of(section.manifest()), List.of());
        return new ProjectionEnvelope(header, List.of(section));
    }

    private static ProjectionBatchPolicy policy(int minBootstrap, int minTip, int maxBlocks,
                                                long maxBytes, long maxRows, long maxHeap) {
        return new ProjectionBatchPolicy(minBootstrap, minTip, maxBlocks,
                Duration.ofSeconds(30), Duration.ofMinutes(5), maxBytes, maxRows, maxHeap, 600,
                64L << 20, 4_000_000);
    }

    // ------------------------------------------------------------- bootstrap

    @Test
    void bootstrapAccumulatesUntilTheMinimumBlockCount() {
        var acc = new ProjectionBatchAccumulator(policy(100, 1, 1000, 1 << 30, 1_000_000, 1L << 30));
        for (long b = 0; b < 99; b++) acc.offer(envelope(b, 1, 10), T0);
        assertThat(acc.decide(false, true, T0).flush()).isFalse();

        acc.offer(envelope(99, 1, 10), T0);
        var decision = acc.decide(false, true, T0);
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.MIN_BLOCKS);
        assertThat(decision.blocks()).isEqualTo(100);
    }

    @Test
    void bootstrapFlushesWhenTheLingerWindowExpiresEvenBelowMinimum() {
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 1 << 30, 1_000_000, 1L << 30));
        acc.offer(envelope(0, 1, 10), T0);
        assertThat(acc.decide(false, true, T0.plusSeconds(29)).flush()).isFalse();

        var decision = acc.decide(false, true, T0.plusSeconds(30));
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.LINGER_EXPIRED);
    }

    // ------------------------------------------------------------- near tip

    @Test
    void nearTipUsesItsOwnBlockTargetNotTheBootstrapOne() {
        // A bootstrap-sized floor at tip would stall for days; the tip target governs instead.
        var acc = new ProjectionBatchAccumulator(policy(10_000, 5, 20_000, 1 << 30, 1_000_000, 1L << 30));
        for (long b = 0; b < 4; b++) acc.offer(envelope(b, 1, 10), T0);
        assertThat(acc.decide(true, true, T0).flush()).isFalse();

        acc.offer(envelope(4, 1, 10), T0);
        var decision = acc.decide(true, true, T0);
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.MIN_BLOCKS);
    }

    /**
     * The correction that matters most at tip: a single eligible block must NOT commit on
     * arrival. Blocks arrive intermittently, so treating "nothing more right now" as a flush
     * reason would commit on almost every arrival and rebuild the tiny-file problem.
     */
    @Test
    void oneEligibleBlockDoesNotCommitImmediatelyAndCommitsWhenFreshnessExpires() {
        var policy = new ProjectionBatchPolicy(10_000, 15, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(5), 1L << 30, 1_000_000, 1L << 30, 600,
                64L << 20, 4_000_000);
        var acc = new ProjectionBatchAccumulator(policy);
        acc.offer(envelope(0, 1, 10), T0);

        // deterministic clock: nothing else arrives for the whole freshness window
        assertThat(acc.decide(true, false, T0).flush()).isFalse();
        assertThat(acc.decide(true, false, T0.plusSeconds(60)).flush()).isFalse();
        assertThat(acc.decide(true, false, T0.plusSeconds(299)).flush()).isFalse();

        var decision = acc.decide(true, false, T0.plusSeconds(300));
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.LINGER_EXPIRED);
        assertThat(decision.blocks()).isEqualTo(1);
    }

    // --------------------------------------------------------- safety bounds

    @Test
    void theRowCeilingIsEnforcedBeforeMaterialisation() {
        // Rows come from stored manifests, so the bound is applied without building any
        // ArchiveRow objects - which is the entire point of bounding here.
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 1L << 30, 1_000, 1L << 30));
        for (long b = 0; b < 10; b++) assertThat(acc.offer(envelope(b, 100, 10), T0)).isEqualTo(ProjectionBatchAccumulator.Offer.ACCEPTED);
        assertThat(acc.rows()).isEqualTo(1_000);

        assertThat(acc.offer(envelope(10, 100, 10), T0)).as("accepting this would breach the row ceiling")
                .isEqualTo(ProjectionBatchAccumulator.Offer.REJECTED_FULL);
        assertThat(acc.decide(false, true, T0).reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MAX_ROWS);
    }

    @Test
    void theHeapCeilingCanBindBeforeTheExplicitRowCeiling() {
        // 600 bytes/row estimate x 1,000 rows = 600 KB; a 300 KB heap bound must bind first.
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 1L << 30, 1_000_000, 300_000));
        assertThat(acc.effectiveMaxRowsForTest()).isLessThan(1_000_000L);
        for (long b = 0; b < 600; b++) acc.offer(envelope(b, 1, 10), T0);
        var decision = acc.decide(false, true, T0);
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.MAX_HEAP);
        assertThat(decision.estimatedHeapBytes()).isGreaterThanOrEqualTo(300_000);
    }

    @Test
    void theEncodedByteCeilingIsEnforced() {
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 10_000, 1_000_000, 1L << 30));
        for (long b = 0; b < 10; b++) acc.offer(envelope(b, 1, 1_000), T0);
        assertThat(acc.encodedBytes()).isEqualTo(10_000);
        assertThat(acc.offer(envelope(10, 1, 1_000), T0)).isEqualTo(ProjectionBatchAccumulator.Offer.REJECTED_FULL);
        assertThat(acc.decide(false, true, T0).reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MAX_BYTES);
    }

    @Test
    void theBlockCeilingIsEnforced() {
        // maxBlocks must be at least every minimum, so the bootstrap minimum is set below it.
        var acc = new ProjectionBatchAccumulator(policy(2, 1, 5, 1L << 30, 1_000_000, 1L << 30));
        for (long b = 0; b < 5; b++) acc.offer(envelope(b, 1, 10), T0);
        assertThat(acc.offer(envelope(5, 1, 10), T0)).isEqualTo(ProjectionBatchAccumulator.Offer.REJECTED_FULL);
        assertThat(acc.decide(false, true, T0).reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MAX_BLOCKS);
    }

    @Test
    void anOversizedSingleEnvelopeIsStillAcceptedWhenTheBatchIsEmpty() {
        // Otherwise one dense block larger than a ceiling would deadlock the consumer forever.
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 100, 10, 1_000));
        assertThat(acc.offer(envelope(0, 10_000, 1_000_000), T0)).isEqualTo(ProjectionBatchAccumulator.Offer.ACCEPTED);
        assertThat(acc.decide(false, true, T0).flush()).isTrue();
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    void forceFlushCommitsWhateverIsHeldForShutdown() {
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 1L << 30, 1_000_000, 1L << 30));
        acc.offer(envelope(0, 1, 10), T0);
        var decision = acc.forceFlush();
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.FORCED);
        assertThat(decision.blocks()).isEqualTo(1);
    }

    @Test
    void resetClearsAccountingSoTheNextBatchStartsClean() {
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 1L << 30, 1_000_000, 1L << 30));
        acc.offer(envelope(0, 5, 100), T0);
        acc.reset();
        assertThat(acc.isEmpty()).isTrue();
        assertThat(acc.rows()).isZero();
        assertThat(acc.encodedBytes()).isZero();
        assertThat(acc.decide(false, true, T0).flush()).isFalse();
    }

    @Test
    void anEmptyAccumulatorNeverFlushes() {
        var acc = new ProjectionBatchAccumulator(policy(1, 1, 10, 1L << 30, 1_000_000, 1L << 30));
        assertThat(acc.decide(false, false, T0.plusSeconds(3600)).flush()).isFalse();
    }

    // -------------------------------------------------------------- policy

    @Test
    void defaultsAreCoherentAndRegimeAware() {
        var d = ProjectionBatchPolicy.defaults();
        assertThat(d.minBlocks(false)).isEqualTo(10_000);
        assertThat(d.minBlocks(true))
                .as("a near-tip minimum of 1 would flush every block on arrival")
                .isGreaterThan(1);
        assertThat(d.maxLinger(true)).isGreaterThan(d.maxLinger(false));
        assertThat(d.effectiveMaxRows()).isLessThanOrEqualTo(d.maxRows());
    }

    /**
     * The near-tip block count is a <em>preferred</em> target; the freshness window is the hard
     * bound. Under slow production the target is never reached, and the linger must still fire.
     */
    @Test
    void underSlowProductionTheFreshnessWindowGovernsNotTheBlockTarget() {
        var policy = nearTipDefaults();
        var acc = new ProjectionBatchAccumulator(policy);

        // one block per minute: only 5 arrive within the freshness window, well under 15
        for (int minute = 0; minute < 5; minute++) {
            acc.offer(envelope(minute, 1, 10), T0.plusSeconds(minute * 60L));
            assertThat(acc.decide(true, true, T0.plusSeconds(minute * 60L)).flush())
                    .as("only %d blocks; the target of 15 is not reached", minute + 1)
                    .isFalse();
        }

        assertThat(acc.decide(true, true, T0.plusSeconds(899)).flush())
                .as("one second before the deadline")
                .isFalse();

        var decision = acc.decide(true, true, T0.plusSeconds(900));
        assertThat(decision.flush()).as("flushes at exactly the linger deadline").isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.LINGER_EXPIRED);
        assertThat(decision.blocks()).isEqualTo(5);
    }

    /** The companion: with fast production the target fires well before the window. */
    @Test
    void reachingTheTargetFlushesEarlierWithMinBlocks() {
        var acc = new ProjectionBatchAccumulator(nearTipDefaults());
        for (int i = 0; i < 50; i++) acc.offer(envelope(i, 1, 10), T0.plusSeconds(i));

        var decision = acc.decide(true, true, T0.plusSeconds(50));
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.MIN_BLOCKS);
        assertThat(decision.blocks()).isEqualTo(50);
    }

    /** Conversely, when blocks arrive quickly the target fires before the window. */
    @Test
    void underFastProductionTheBlockTargetFiresBeforeTheFreshnessWindow() {
        var policy = new ProjectionBatchPolicy(10_000, 15, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(5), 1L << 30, 1_000_000, 1L << 30, 600,
                64L << 20, 4_000_000);
        var acc = new ProjectionBatchAccumulator(policy);
        for (int i = 0; i < 15; i++) acc.offer(envelope(i, 1, 10), T0.plusSeconds(i));

        var decision = acc.decide(true, true, T0.plusSeconds(15));
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.MIN_BLOCKS);
    }

    // ------------------------------------------------ singleton safety guard

    @Test
    void anEnvelopeBeyondTheAbsoluteGuardIsRefusedRatherThanRiskingOom() {
        // Guards set implausibly low so the case is reachable; in production they sit far
        // above any valid Cardano block, so this never fires in normal operation.
        var policy = new ProjectionBatchPolicy(2, 1, 10, Duration.ofSeconds(30), Duration.ofMinutes(5),
                1L << 30, 1_000_000, 1L << 30, 600, /*singleton bytes*/ 1_000, /*singleton rows*/ 10);
        var acc = new ProjectionBatchAccumulator(policy);

        assertThat(acc.offer(envelope(0, 5_000, 10), T0))
                .as("a projection this large from one block indicates corruption, not density")
                .isEqualTo(ProjectionBatchAccumulator.Offer.REJECTED_UNSAFE);
        assertThat(acc.isEmpty()).isTrue();
        assertThat(acc.stats().largestRejectedSingletonBytes()).isPositive();
    }

    @Test
    void anOversizedButSafeSingletonIsAcceptedAndCounted() {
        var policy = new ProjectionBatchPolicy(2, 1, 10, Duration.ofSeconds(30), Duration.ofMinutes(5),
                /*maxEncodedBytes*/ 100, 10, 1L << 30, 600, 64L << 20, 4_000_000);
        var acc = new ProjectionBatchAccumulator(policy);

        assertThat(acc.offer(envelope(0, 50, 5_000), T0))
                .isEqualTo(ProjectionBatchAccumulator.Offer.ACCEPTED);
        var stats = acc.stats();
        assertThat(stats.oversizedSingletons()).isEqualTo(1);
        assertThat(stats.singletonHighWatermarkBytes()).isPositive();
    }

    // ------------------------------------------------------- observability

    @Test
    void statsReportLargestEnvelopeBytesAndRows() {
        var acc = new ProjectionBatchAccumulator(policy(10_000, 1, 20_000, 1L << 30, 1_000_000, 1L << 30));
        acc.offer(envelope(0, 10, 100), T0);
        acc.offer(envelope(1, 250, 4_000), T0);
        acc.offer(envelope(2, 5, 50), T0);

        var stats = acc.stats();
        assertThat(stats.largestEnvelopeRows()).isEqualTo(250);
        assertThat(stats.largestEnvelopeEncodedBytes()).isEqualTo(4_000);
        assertThat(stats.oversizedSingletons()).isZero();
    }

    // ------------------------------- near-tip defaults: 50 blocks OR 15 minutes

    /** Production near-tip policy: preferred target 50 envelopes, hard bound 15 minutes. */
    private static ProjectionBatchPolicy nearTipDefaults() {
        return new ProjectionBatchPolicy(10_000, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15), 1L << 30, 1_000_000, 1L << 30, 600,
                64L << 20, 4_000_000);
    }

    @Test
    void fortyNineEnvelopesDoNotFlushBeforeTheDeadline() {
        var acc = new ProjectionBatchAccumulator(nearTipDefaults());
        for (int i = 0; i < 49; i++) {
            // arrival spacing kept inside the 15-minute window so only the block target is in play
            acc.offer(envelope(i, 1, 10), T0.plusSeconds(i * 10L));
            assertThat(acc.decide(true, true, T0.plusSeconds(i * 10L)).flush())
                    .as("%d envelopes, target is 50", i + 1)
                    .isFalse();
        }
        // still short of both the target and the 15-minute deadline
        assertThat(acc.decide(true, true, T0.plusSeconds(880)).flush()).isFalse();
    }

    @Test
    void theFiftiethEnvelopeFlushesWithMinBlocks() {
        var acc = new ProjectionBatchAccumulator(nearTipDefaults());
        for (int i = 0; i < 49; i++) acc.offer(envelope(i, 1, 10), T0.plusSeconds(i));
        assertThat(acc.decide(true, true, T0.plusSeconds(49)).flush()).isFalse();

        acc.offer(envelope(49, 1, 10), T0.plusSeconds(50));
        var decision = acc.decide(true, true, T0.plusSeconds(50));
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.MIN_BLOCKS);
        assertThat(decision.blocks()).isEqualTo(50);
    }

    @Test
    void aPartialBatchFlushesAtExactlyFifteenMinutes() {
        // At 20 s slots the deadline typically fires around 40-50 blocks; that is the expected
        // steady-state outcome, not a missed target.
        var acc = new ProjectionBatchAccumulator(nearTipDefaults());
        for (int i = 0; i < 45; i++) acc.offer(envelope(i, 1, 10), T0.plusSeconds(i * 20L));

        assertThat(acc.decide(true, true, T0.plusSeconds(899)).flush()).isFalse();
        var decision = acc.decide(true, true, T0.plusSeconds(900));
        assertThat(decision.flush()).isTrue();
        assertThat(decision.reason()).isEqualTo(ProjectionBatchDecision.Reason.LINGER_EXPIRED);
        assertThat(decision.blocks()).isEqualTo(45);
    }

    @Test
    void safetyGuardsOverrideBothNearTipTargets() {
        // Byte ceiling fires well before 50 envelopes and well before 15 minutes.
        var bytes = new ProjectionBatchAccumulator(new ProjectionBatchPolicy(10_000, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15), 5_000, 1_000_000, 1L << 30, 600,
                64L << 20, 4_000_000));
        for (int i = 0; i < 5; i++) bytes.offer(envelope(i, 1, 1_000), T0);
        assertThat(bytes.decide(true, true, T0).reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MAX_BYTES);

        // Row ceiling.
        var rows = new ProjectionBatchAccumulator(new ProjectionBatchPolicy(10_000, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15), 1L << 30, 100, 1L << 30, 600,
                64L << 20, 4_000_000));
        for (int i = 0; i < 10; i++) rows.offer(envelope(i, 10, 10), T0);
        assertThat(rows.decide(true, true, T0).reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MAX_ROWS);

        // Heap ceiling.
        var heap = new ProjectionBatchAccumulator(new ProjectionBatchPolicy(10_000, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15), 1L << 30, 1_000_000, 6_000, 600,
                64L << 20, 4_000_000));
        for (int i = 0; i < 10; i++) heap.offer(envelope(i, 1, 10), T0);
        assertThat(heap.decide(true, true, T0).reason())
                .isEqualTo(ProjectionBatchDecision.Reason.MAX_HEAP);

        // Singleton guard refuses rather than flushing.
        var guard = new ProjectionBatchAccumulator(new ProjectionBatchPolicy(10_000, 50, 20_000,
                Duration.ofSeconds(30), Duration.ofMinutes(15), 1L << 30, 1_000_000, 1L << 30, 600,
                1_000, 10));
        assertThat(guard.offer(envelope(0, 5_000, 10), T0))
                .isEqualTo(ProjectionBatchAccumulator.Offer.REJECTED_UNSAFE);
    }

    @Test
    void flushReasonDistributionIsRecordedForMetrics() {
        var acc = new ProjectionBatchAccumulator(nearTipDefaults());
        for (int i = 0; i < 50; i++) acc.offer(envelope(i, 1, 10), T0);
        var byTarget = acc.decide(true, true, T0);
        acc.recordFlush(byTarget);
        acc.reset();

        acc.offer(envelope(100, 1, 10), T0);
        var byLinger = acc.decide(true, true, T0.plusSeconds(900));
        acc.recordFlush(byLinger);

        var reasons = acc.stats().flushReasons();
        assertThat(reasons).containsEntry(ProjectionBatchDecision.Reason.MIN_BLOCKS, 1L);
        assertThat(reasons).containsEntry(ProjectionBatchDecision.Reason.LINGER_EXPIRED, 1L);
    }
}
