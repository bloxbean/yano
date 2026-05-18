package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

import java.util.List;

/**
 * Provides candidate intersection points for starting or rebuilding an upstream
 * peer session.
 */
@FunctionalInterface
public interface SyncStartPointProvider {
    /**
     * Return candidate points ordered from most preferred to least preferred.
     *
     * <p>Implementations should return fallback points, not just one point, so
     * a rebuilt peer can recover when the preferred header point is not accepted
     * by the upstream.</p>
     */
    List<Point> candidateStartPoints();
}
