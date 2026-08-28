package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

/**
 * Yano extension for rolling chain state back to an exact chain point.
 *
 * <p>The upstream {@code ChainState} contract exposes only a slot-based
 * rollback method. Byron epoch-boundary blocks can share a slot with their
 * successor main block, so canonical rollback must retain the block hash.</p>
 */
public interface PointRollbackCapable {
    void rollbackTo(Point target);
}
