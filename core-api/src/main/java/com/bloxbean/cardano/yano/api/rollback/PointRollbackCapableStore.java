package com.bloxbean.cardano.yano.api.rollback;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

/**
 * Exact-point companion to the legacy slot-based adhoc rollback contract.
 */
public interface PointRollbackCapableStore extends RollbackCapableStore {

    /**
     * Roll back persisted derived state to the supplied canonical point.
     * A null hash denotes origin, matching Yaci's {@code Point.ORIGIN}.
     */
    void rollbackToPoint(Point target);
}
