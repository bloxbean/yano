package com.bloxbean.cardano.yano.archive.core.address;

public record ResolvedOutput(byte[] addressKey, byte[] paymentCredential, byte[] stakeCredential) { }
