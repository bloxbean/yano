package com.bloxbean.cardano.yano.api.wallet;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;

/** Index answers are relative to indexedThrough, not necessarily the network tip. */
public record WalletIndexCoverage(boolean enabled, boolean completeFromOrigin,
                                  ChainPoint from, ChainPoint indexedThrough,
                                  String identity, String unavailableReason) {
    public boolean available() { return enabled && unavailableReason == null; }
}
