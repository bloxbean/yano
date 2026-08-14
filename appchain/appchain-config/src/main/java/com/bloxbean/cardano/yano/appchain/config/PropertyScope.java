package com.bloxbean.cardano.yano.appchain.config;

/** Safety and placement scope for one app-chain property. */
public enum PropertyScope {
    CONSENSUS_SHARED,
    CLUSTER_SHARED,
    NODE_LOCAL,
    SECRET,
    INFRASTRUCTURE,
    CLIENT
}
