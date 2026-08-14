package com.bloxbean.cardano.yano.api.appchain;

/** Deterministic read-only membership epochs resolved from finalized chain history. */
@FunctionalInterface
public interface AppChainMembershipView {
    AppChainMembershipEpoch epochAt(long height);
}
