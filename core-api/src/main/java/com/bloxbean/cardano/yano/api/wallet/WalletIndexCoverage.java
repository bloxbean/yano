package com.bloxbean.cardano.yano.api.wallet;

/** Index answers are relative to indexedThrough, not necessarily the network tip. */
public record WalletIndexCoverage(boolean enabled, boolean completeFromOrigin,
                                  WalletChainPoint from, WalletChainPoint indexedThrough,
                                  String identity, String unavailableReason) {
    public boolean available() { return enabled && unavailableReason == null; }
}
