package com.bloxbean.cardano.yano.archive.core.projection;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pointer index may only be reclaimed when every ADR-039 gate holds. These tests exist
 * mainly to prove the <em>denials</em>: deleting the index early is silent and irreversible,
 * and "the tip is in Conway" is the tempting wrong answer.
 */
class PointerHistoryCleanupPolicyTest {

    private static final long PRE_CONWAY_BLOCK = 1_000_000;
    private static final long PRE_CONWAY_SLOT = 50_000_000;

    /** Every gate satisfied; individual tests weaken exactly one. */
    private static PointerHistoryCleanupPolicy.Observation allGatesPass() {
        return new PointerHistoryCleanupPolicy.Observation(
                OptionalLong.of(PRE_CONWAY_BLOCK), OptionalLong.of(PRE_CONWAY_SLOT),
                PRE_CONWAY_BLOCK + 5_000,   // archive-finality safe
                PRE_CONWAY_BLOCK + 10_000,  // contributors well past
                -1,                          // nothing replayable
                PRE_CONWAY_SLOT + 1_000,     // rollback floor past the boundary
                0,                           // no holds
                true);                       // sections durable
    }

    @Test
    void cleanupIsAllowedWhenEveryGatePasses() {
        var decision = PointerHistoryCleanupPolicy.evaluate(allGatesPass());
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.throughSlot()).hasValue(PRE_CONWAY_SLOT);
        assertThat(decision.reason()).isEmpty();
    }

    @Test
    void gate1_noCleanupBeforeTheChainCrossesConway() {
        var o = new PointerHistoryCleanupPolicy.Observation(
                OptionalLong.empty(), OptionalLong.empty(), 9_999_999, 9_999_999, -1,
                PRE_CONWAY_SLOT + 1, 0, true);
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("has not crossed the Conway boundary");
    }

    @Test
    void gate1_noCleanupWhileTheBoundaryIsStillInsideTheFinalityWindow() {
        var base = allGatesPass();
        var o = new PointerHistoryCleanupPolicy.Observation(base.finalPreConwayBlock(),
                base.finalPreConwaySlot(), PRE_CONWAY_BLOCK - 1, base.contributorsCompleteThroughBlock(),
                base.oldestReplayableBlock(), base.commonRollbackFloorSlot(),
                base.activePointerRetentionHolds(), base.preConwaySectionsDurable());
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("not yet archive-finality safe");
    }

    @Test
    void gate2_noCleanupWhileAContributorIsBehindTheBoundary() {
        var base = allGatesPass();
        var o = new PointerHistoryCleanupPolicy.Observation(base.finalPreConwayBlock(),
                base.finalPreConwaySlot(), base.archiveEligibleThroughBlock(), PRE_CONWAY_BLOCK - 1,
                base.oldestReplayableBlock(), base.commonRollbackFloorSlot(),
                base.activePointerRetentionHolds(), base.preConwaySectionsDurable());
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("short of the final pre-Conway block");
    }

    @Test
    void gate3_noCleanupWhileAPreConwayBlockRemainsReplayable() {
        var base = allGatesPass();
        var o = new PointerHistoryCleanupPolicy.Observation(base.finalPreConwayBlock(),
                base.finalPreConwaySlot(), base.archiveEligibleThroughBlock(),
                base.contributorsCompleteThroughBlock(), PRE_CONWAY_BLOCK - 500,
                base.commonRollbackFloorSlot(), base.activePointerRetentionHolds(),
                base.preConwaySectionsDurable());
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("can still replay pre-Conway block");
    }

    @Test
    void gate4_noCleanupWhileTheRollbackFloorIsAtOrBeforeTheBoundary() {
        var base = allGatesPass();
        var o = new PointerHistoryCleanupPolicy.Observation(base.finalPreConwayBlock(),
                base.finalPreConwaySlot(), base.archiveEligibleThroughBlock(),
                base.contributorsCompleteThroughBlock(), base.oldestReplayableBlock(),
                PRE_CONWAY_SLOT, base.activePointerRetentionHolds(), base.preConwaySectionsDurable());
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("not past the final pre-Conway slot");
    }

    @Test
    void gate4_unboundedRetentionBlocksCleanupEntirely() {
        // Floor 0 means "can roll back anywhere", which includes pre-Conway.
        var base = allGatesPass();
        var o = new PointerHistoryCleanupPolicy.Observation(base.finalPreConwayBlock(),
                base.finalPreConwaySlot(), base.archiveEligibleThroughBlock(),
                base.contributorsCompleteThroughBlock(), base.oldestReplayableBlock(),
                0, base.activePointerRetentionHolds(), base.preConwaySectionsDurable());
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("unbounded");
    }

    @Test
    void gate5_noCleanupWhileARetentionHoldExists() {
        var base = allGatesPass();
        var o = new PointerHistoryCleanupPolicy.Observation(base.finalPreConwayBlock(),
                base.finalPreConwaySlot(), base.archiveEligibleThroughBlock(),
                base.contributorsCompleteThroughBlock(), base.oldestReplayableBlock(),
                base.commonRollbackFloorSlot(), 2, base.preConwaySectionsDurable());
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("retention hold");
    }

    @Test
    void gate6_noCleanupWhilePreConwaySectionsAreNotDurable() {
        var base = allGatesPass();
        var o = new PointerHistoryCleanupPolicy.Observation(base.finalPreConwayBlock(),
                base.finalPreConwaySlot(), base.archiveEligibleThroughBlock(),
                base.contributorsCompleteThroughBlock(), base.oldestReplayableBlock(),
                base.commonRollbackFloorSlot(), base.activePointerRetentionHolds(), false);
        var decision = PointerHistoryCleanupPolicy.evaluate(o);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).get().asString().contains("not yet durable");
    }

    @Test
    void aConwayTipAloneIsNotSufficient() {
        // The specific mistake this policy exists to prevent: everything else unmet.
        var o = new PointerHistoryCleanupPolicy.Observation(
                OptionalLong.of(PRE_CONWAY_BLOCK), OptionalLong.of(PRE_CONWAY_SLOT),
                PRE_CONWAY_BLOCK - 1, 0, 0, 0, 3, false);
        assertThat(PointerHistoryCleanupPolicy.evaluate(o).allowed()).isFalse();
    }

    @Test
    void aSinkReceiptIsDeliberatelyNotRequired() {
        // The resolved pointer outcome is already durable in the outbox envelope, so waiting
        // on a lagging sink would retain the index far longer than correctness needs.
        assertThat(PointerHistoryCleanupPolicy.evaluate(allGatesPass()).allowed()).isTrue();
    }
}
