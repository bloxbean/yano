package com.bloxbean.cardano.yano.archive.core.dataset;

public record RewardFact(byte[] stakeCredential, String credentialType, byte[] poolHash,
                         String rewardType, long earnedEpoch, long spendableEpoch,
                         long amount, String sourceId) { }
