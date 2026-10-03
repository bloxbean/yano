package org.yanoproject.runtime;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.yanoproject.runtime.chain.NearestPointLookup;

import java.util.ArrayList;
import java.util.List;

/**
 * The older local points a chain-sync client offers for intersection when the upstream does not have its tip.
 *
 * <p>They are the points the Haskell chain-sync client offers after its tip (ouroboros-consensus
 * {@code MiniProtocol/ChainSync/Client.hs}, {@code mkOffsets}): the blocks a Fibonacci number 1, 2, 3, 5, 8, ...
 * back from the tip while fewer than k back, then the block k back, the deepest a rollback may go, or the first
 * block of a shorter chain. Newest first. An upstream that has none of them is not one this node can follow.</p>
 */
final class IntersectPoints {

    private IntersectPoints() {
    }

    static List<Point> olderThan(ChainState chainState, ChainTip tip, long securityParam) {
        List<Point> points = new ArrayList<>();
        if (tip == null || !(chainState instanceof NearestPointLookup lookup)) {
            return points;
        }
        long depth = Math.min(Math.max(1L, securityParam), tip.getBlockNumber());
        List<Long> offsets = new ArrayList<>();
        long offset = 1;
        long previous = 1;
        while (offset < depth) {
            offsets.add(offset);
            long next = offset + previous;
            previous = offset;
            offset = next;
        }
        if (depth > 0) {
            offsets.add(depth);
        }
        for (long back : offsets) {
            Long slot = chainState.getSlotByBlockNumber(tip.getBlockNumber() - back);
            Point point = slot != null ? lookup.findNearestPointAtOrBefore(slot) : null;
            if (point != null && point.getSlot() == slot) {
                points.add(point);
            }
        }
        return points;
    }
}
