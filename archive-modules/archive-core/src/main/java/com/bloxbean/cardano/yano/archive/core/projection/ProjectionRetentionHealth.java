package com.bloxbean.cardano.yano.archive.core.projection;

import java.util.Objects;
import java.util.Optional;

/**
 * Retention-health assertion of ADR-039 §6 and invariant 14:
 *
 * <pre>runtime common rollback floor &lt;= oldest active required slot</pre>
 *
 * <p>A <em>lower</em> floor means <em>more</em> replay capability, so this is a
 * precondition rather than a term in eligibility. Equality is an exhaustion warning;
 * a greater floor means required replay data was already lost, and the correct
 * response is a visible fail-closed pause — never narrowing archive eligibility,
 * skipping the affected work, or pruning the required source.
 */
public record ProjectionRetentionHealth(Status status, long commonRollbackFloorSlot,
                                        long oldestActiveRequiredSlot, Optional<String> detail) {

    public enum Status {
        /** Floor is safely below every active requirement. */
        HEALTHY,
        /** Floor has reached the oldest requirement; the next prune would cross it. */
        EXHAUSTED,
        /** Floor has passed a requirement: replay data required by pending work is gone. */
        VIOLATED
    }

    public ProjectionRetentionHealth {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }

    /**
     * @param commonRollbackFloorSlot max of {@code RollbackCapableStore.getRollbackFloorSlot()};
     *                                0 means unbounded retention
     * @param oldestActiveRequiredSlot oldest slot any pending envelope, artifact reference or
     *                                 recomputation input closure still requires, or -1 when
     *                                 nothing is pending
     */
    public static ProjectionRetentionHealth evaluate(long commonRollbackFloorSlot, long oldestActiveRequiredSlot) {
        if (oldestActiveRequiredSlot < 0) {
            return new ProjectionRetentionHealth(Status.HEALTHY, commonRollbackFloorSlot,
                    oldestActiveRequiredSlot, Optional.empty());
        }
        if (commonRollbackFloorSlot < oldestActiveRequiredSlot) {
            return new ProjectionRetentionHealth(Status.HEALTHY, commonRollbackFloorSlot,
                    oldestActiveRequiredSlot, Optional.empty());
        }
        if (commonRollbackFloorSlot == oldestActiveRequiredSlot) {
            return new ProjectionRetentionHealth(Status.EXHAUSTED, commonRollbackFloorSlot, oldestActiveRequiredSlot,
                    Optional.of("retention margin exhausted at slot " + oldestActiveRequiredSlot
                            + "; the next prune would remove data pending projection work still requires"));
        }
        return new ProjectionRetentionHealth(Status.VIOLATED, commonRollbackFloorSlot, oldestActiveRequiredSlot,
                Optional.of("common rollback floor " + commonRollbackFloorSlot
                        + " has passed the oldest active required slot " + oldestActiveRequiredSlot
                        + "; required replay data was lost under a lagging sink"));
    }

    public boolean allowsProgress() {
        return status != Status.VIOLATED;
    }
}
