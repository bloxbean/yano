package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

/** Truthful coverage of the disposable read model. */
public enum IndexCoverage {
    FULL,
    PARTIAL,
    REBUILDING,
    FAILED
}
