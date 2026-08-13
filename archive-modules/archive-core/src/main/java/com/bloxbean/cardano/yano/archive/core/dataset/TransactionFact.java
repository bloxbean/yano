package com.bloxbean.cardano.yano.archive.core.dataset;

public record TransactionFact(byte[] txHash, int txIndex, boolean valid, long fee) { }
