package com.bloxbean.cardano.yano.archive.core.dataset;

public record DrepDistributionFact(String drepType, byte[] credential, long amount,
                                   Long storedExpiry, long dormantEpochs,
                                   Long effectiveExpiry, boolean active) { }
