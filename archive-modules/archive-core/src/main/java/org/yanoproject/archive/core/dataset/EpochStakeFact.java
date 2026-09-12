package org.yanoproject.archive.core.dataset;

public record EpochStakeFact(String credentialType, byte[] stakeCredential, byte[] poolHash, long amount) { }
