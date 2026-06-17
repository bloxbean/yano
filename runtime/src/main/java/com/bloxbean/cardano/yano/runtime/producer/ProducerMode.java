package com.bloxbean.cardano.yano.runtime.producer;

/**
 * Runtime block-production strategy selected by assembly/startup code.
 */
public enum ProducerMode {
    DEVNET,
    SLOT_LEADER,
    DEVNET_TIME_TRAVEL,
    SLOT_LEADER_TIME_TRAVEL
}
