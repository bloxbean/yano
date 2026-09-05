package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.math.BigInteger;

/**
 * Narrow, read-only, epoch-pinned ledger view. A handle is valid only during
 * the observer callback that receives it; hosts must reject retained use.
 */
public interface L1EpochState extends AutoCloseable {
    long previousEpoch();

    long newEpoch();

    /**
     * Parameters effective in {@link #newEpoch()}. Hosts may reject any other epoch;
     * observers must not use this method to infer the era of a previous-epoch dataset.
     */
    ProtocolParamsView protocolParams(long effectiveEpoch);

    /** Whether the previous-epoch snapshot is available, including a completed empty snapshot. */
    boolean hasStakeSnapshot(long snapshotEpoch);

    void forEachStakeEntry(long snapshotEpoch, StakeEntryConsumer consumer);

    boolean hasProposalStatusSnapshot(long snapshotEpoch);

    boolean hasDRepDistributionSnapshot(long snapshotEpoch);

    void forEachProposalStatus(long snapshotEpoch, ProposalStatusConsumer consumer);

    void forEachDRepDistributionEntry(long snapshotEpoch,
                                      DRepDistributionConsumer consumer);

    @Override
    default void close() {
    }

    @FunctionalInterface
    interface StakeEntryConsumer {
        void accept(int credType, byte[] credHash, BigInteger coin, byte[] poolHash);
    }

    @FunctionalInterface
    interface ProposalStatusConsumer {
        void accept(byte[] transactionId, int governanceActionIndex,
                    GovernanceActionType actionType, GovernanceProposalStatus status,
                    GovernanceProposalStatusReason reason, long proposedEpoch,
                    long expiresAfterEpoch);
    }

    @FunctionalInterface
    interface DRepDistributionConsumer {
        void accept(int drepType, byte[] drepHash, BigInteger coin);
    }
}
