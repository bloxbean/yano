package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Decides whether the derived as-of pointer index may be reclaimed (ADR-039).
 *
 * <p>Pointer addresses exist only pre-Conway, so the index eventually has no readers. But
 * "the tip is in Conway" is nowhere near sufficient to delete it, and treating it as
 * sufficient is the obvious mistake this class exists to prevent: a rollback, a lagging
 * contributor, or a replay of a retained pre-Conway body would all still need resolution
 * that is no longer possible.
 *
 * <p>Every gate must hold. They are evaluated in order and the first failure is reported, so
 * an operator sees which condition is holding cleanup back rather than an opaque "not yet".
 */
/**
 * <strong>NOT YET WIRED.</strong> This policy is implemented and tested but has no production
 * caller, deliberately: the pointer index measures <strong>0.68 MiB on a full preprod
 * chainstate</strong> (17,036 entries at 42 bytes), so there is nothing worth reclaiming and
 * running cleanup would add risk for no benefit.
 *
 * <p>It exists now rather than later because the gates are the hard part and they were specified
 * with the index design; deriving them again under pressure, when an index has actually grown,
 * is how a cleanup deletes something a pointer still needs. Wire it when a real network shows the
 * index growing enough to matter, and only with the completeness state it depends on.
 */
public final class PointerHistoryCleanupPolicy {

    /** Everything the decision depends on, gathered by the caller. */
    public record Observation(
            /** Final pre-Conway block, empty until the chain has crossed the boundary. */
            OptionalLong finalPreConwayBlock,
            /** Slot of that block. */
            OptionalLong finalPreConwaySlot,
            /** Greatest block eligible under archive finality, i.e. tip - archiveFinalityBlocks. */
            long archiveEligibleThroughBlock,
            /** Greatest block every required contributor has durably completed. */
            long contributorsCompleteThroughBlock,
            /** Oldest block any incomplete contributor could still replay; -1 when none. */
            long oldestReplayableBlock,
            /** Runtime common rollback floor slot; 0 means unbounded retention. */
            long commonRollbackFloorSlot,
            /** Active artifact leases or reconciliation markers requiring pointer history. */
            int activePointerRetentionHolds,
            /** Whether pre-Conway envelopes are durable in the outbox or acknowledged. */
            boolean preConwaySectionsDurable) {

        public Observation {
            Objects.requireNonNull(finalPreConwayBlock, "finalPreConwayBlock");
            Objects.requireNonNull(finalPreConwaySlot, "finalPreConwaySlot");
        }
    }

    /** Cleanup authorisation, with the reason when it is withheld. */
    public record Decision(boolean allowed, Optional<String> reason, OptionalLong throughSlot) {
        public Decision {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(throughSlot, "throughSlot");
        }

        static Decision deny(String reason) {
            return new Decision(false, Optional.of(reason), OptionalLong.empty());
        }

        static Decision allow(long throughSlot) {
            return new Decision(true, Optional.empty(), OptionalLong.of(throughSlot));
        }
    }

    private PointerHistoryCleanupPolicy() {}

    public static Decision evaluate(Observation o) {
        Objects.requireNonNull(o, "observation");

        // 1. The chain must actually have crossed Conway, and that crossing must itself be
        //    archive-finality safe. A boundary inside the rollback window can still be undone.
        if (o.finalPreConwayBlock().isEmpty() || o.finalPreConwaySlot().isEmpty()) {
            return Decision.deny("the chain has not crossed the Conway boundary");
        }
        long finalPreConwayBlock = o.finalPreConwayBlock().getAsLong();
        if (o.archiveEligibleThroughBlock() < finalPreConwayBlock) {
            return Decision.deny("the Conway boundary at block " + finalPreConwayBlock
                    + " is not yet archive-finality safe (eligible through "
                    + o.archiveEligibleThroughBlock() + ")");
        }

        // 2. Every required contributor must have durably produced sections through the last
        //    block that could contain a pointer address.
        if (o.contributorsCompleteThroughBlock() < finalPreConwayBlock) {
            return Decision.deny("contributors have only completed through block "
                    + o.contributorsCompleteThroughBlock() + ", short of the final pre-Conway block "
                    + finalPreConwayBlock);
        }

        // 3. No contributor may still be able to replay a pre-Conway body, because that replay
        //    would need resolution the index no longer provides.
        long oldestReplayable = o.oldestReplayableBlock();
        if (oldestReplayable >= 0 && oldestReplayable <= finalPreConwayBlock) {
            return Decision.deny("a contributor can still replay pre-Conway block " + oldestReplayable);
        }

        // 4. The rollback floor must be past the boundary; otherwise a rollback could return the
        //    chain to a range that needs pointer resolution. Floor 0 means unbounded retention,
        //    which by definition includes pre-Conway.
        long floor = o.commonRollbackFloorSlot();
        long finalPreConwaySlot = o.finalPreConwaySlot().getAsLong();
        if (floor == 0) {
            return Decision.deny("rollback retention is unbounded, so pre-Conway blocks remain reachable");
        }
        if (floor <= finalPreConwaySlot) {
            return Decision.deny("the common rollback floor " + floor
                    + " is not past the final pre-Conway slot " + finalPreConwaySlot);
        }

        // 5. Nothing may still be holding pointer history open.
        if (o.activePointerRetentionHolds() > 0) {
            return Decision.deny(o.activePointerRetentionHolds()
                    + " active retention hold(s) still require pointer history");
        }

        // 6. The resolved outcomes must already be durable in their sections. The sink receipt
        //    is deliberately NOT required: the resolved value is recorded in the outbox
        //    envelope, so durability there is sufficient and waiting on a lagging sink would
        //    retain the index far longer than correctness needs.
        if (!o.preConwaySectionsDurable()) {
            return Decision.deny("pre-Conway projection sections are not yet durable");
        }

        return Decision.allow(finalPreConwaySlot);
    }
}
