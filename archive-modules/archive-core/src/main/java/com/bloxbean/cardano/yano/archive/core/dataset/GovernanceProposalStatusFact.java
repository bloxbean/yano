package com.bloxbean.cardano.yano.archive.core.dataset;

public record GovernanceProposalStatusFact(byte[] txHash, int governanceActionIndex,
                                           String actionType, String observationPhase,
                                           String statusCode, String decisionReason,
                                           long deposit, byte[] returnAddress,
                                           long submittedEpoch, long expiresAfterEpoch) { }
