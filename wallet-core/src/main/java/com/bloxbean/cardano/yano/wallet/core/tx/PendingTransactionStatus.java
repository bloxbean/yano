package com.bloxbean.cardano.yano.wallet.core.tx;

public enum PendingTransactionStatus {
    DRAFTED,
    SUBMITTED,
    PENDING,
    CONFIRMED,
    ROLLED_BACK,
    EXPIRED,
    FAILED
}
