package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

/** Exact-point rollback helpers shared by runtime orchestration paths. */
public final class ChainStateRollback {
    private ChainStateRollback() {
    }

    public static void rollbackToPoint(ChainState chainState, Point target) {
        if (!(chainState instanceof PointRollbackCapable capable)) {
            throw new IllegalStateException("ChainState does not support exact-point rollback: "
                    + chainState.getClass().getName());
        }
        capable.rollbackTo(target);
    }

    public static Point pointOf(ChainTip tip) {
        if (tip == null) return Point.ORIGIN;
        return new Point(tip.getSlot(), HexUtil.encodeHexString(tip.getBlockHash()));
    }
}
