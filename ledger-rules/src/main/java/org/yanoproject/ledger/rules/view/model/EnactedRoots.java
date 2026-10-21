package org.yanoproject.ledger.rules.view.model;

import java.util.Objects;

/**
 * The last enacted governance action per purpose (Haskell {@code GovRelation StrictMaybe}); a
 * {@code null} root means nothing of that purpose has been enacted yet.
 *
 * <p>Roots change only at an epoch boundary (enactment), never inside an epoch, so overlays pass
 * them through unchanged.</p>
 */
public record EnactedRoots(GovActionId pparamUpdate, GovActionId hardFork, GovActionId committee,
                           GovActionId constitution) {

    public static final EnactedRoots NONE = new EnactedRoots(null, null, null, null);

    /** @return the root for a purpose, or {@code null} */
    public GovActionId root(GovPurpose purpose) {
        return switch (Objects.requireNonNull(purpose, "purpose")) {
            case PPARAM_UPDATE -> pparamUpdate;
            case HARD_FORK -> hardFork;
            case COMMITTEE -> committee;
            case CONSTITUTION -> constitution;
        };
    }
}
