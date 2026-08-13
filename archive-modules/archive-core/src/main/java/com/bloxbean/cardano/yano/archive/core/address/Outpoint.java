package com.bloxbean.cardano.yano.archive.core.address;

public record Outpoint(byte[] txHash, int outputIndex) { }
