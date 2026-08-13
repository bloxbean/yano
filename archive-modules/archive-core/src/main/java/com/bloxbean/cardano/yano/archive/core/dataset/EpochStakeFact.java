package com.bloxbean.cardano.yano.archive.core.dataset;

public record EpochStakeFact(String credentialType, byte[] stakeCredential, byte[] poolHash, long amount) { }
