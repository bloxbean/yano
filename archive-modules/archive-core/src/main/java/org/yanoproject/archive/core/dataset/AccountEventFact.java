package org.yanoproject.archive.core.dataset;

public record AccountEventFact(byte[] stakeCredential, String credentialType, String eventType,
                               byte[] txHash, int txIndex, long eventIndex,
                               byte[] poolHash, String drepType, byte[] drepCredential, Long amount) { }
