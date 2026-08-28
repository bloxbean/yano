package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;

/** Resolves the canonical stored main-block point at or before a slot. */
public interface NearestPointLookup {
    Point findNearestPointAtOrBefore(long slot);
}
