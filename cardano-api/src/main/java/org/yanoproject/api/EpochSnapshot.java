package org.yanoproject.api;

import com.bloxbean.cardano.yaci.core.model.governance.GovActionType;
import org.yanoproject.api.governance.GovernanceProposalStatus;
import org.yanoproject.api.governance.GovernanceProposalStatusReason;
import org.yanoproject.api.model.ProtocolParamsSnapshot;

import java.math.BigInteger;

/**
 * Read-only ledger view pinned to one completed {@link EpochBoundary}. Every read sees the same
 * consistent store snapshot; any use after {@link #close()} is rejected.
 *
 * <p>Stake reads accept only {@link #previousEpoch()}; protocol parameters and governance reads accept
 * only {@link #newEpoch()}. Any other epoch is rejected with {@link IllegalArgumentException}.</p>
 */
public interface EpochSnapshot extends AutoCloseable {
    long previousEpoch();

    long newEpoch();

    /** Parameters effective in {@link #newEpoch()}. */
    ProtocolParamsSnapshot protocolParams(long effectiveEpoch);

    /** Whether the stake snapshot of {@link #previousEpoch()} is complete, including an empty one. */
    boolean hasStakeSnapshot(long snapshotEpoch);

    /** Entries of the {@link #previousEpoch()} stake snapshot in {@code (credType, credHash)} order. */
    void forEachStakeEntry(long snapshotEpoch, StakeEntryConsumer consumer);

    /** Whether the proposal-status snapshot of {@link #newEpoch()} exists, including an empty one. */
    boolean hasProposalStatusSnapshot(long snapshotEpoch);

    /** Proposals of the {@link #newEpoch()} snapshot in {@code (transactionId, index)} order. */
    void forEachProposalStatus(long snapshotEpoch, ProposalStatusConsumer consumer);

    /** Whether the DRep distribution snapshot of {@link #newEpoch()} exists, including an empty one. */
    boolean hasDRepDistributionSnapshot(long snapshotEpoch);

    /** Entries of the {@link #newEpoch()} DRep distribution in {@code (drepType, drepHash)} order. */
    void forEachDRepDistributionEntry(long snapshotEpoch, DRepDistributionConsumer consumer);

    /** Releases the store snapshot. Idempotent. */
    @Override
    void close();

    @FunctionalInterface
    interface StakeEntryConsumer {
        void accept(int credType, byte[] credHash, BigInteger coin, byte[] poolHash);
    }

    @FunctionalInterface
    interface ProposalStatusConsumer {
        void accept(byte[] transactionId, int governanceActionIndex,
                    GovActionType actionType, GovernanceProposalStatus status,
                    GovernanceProposalStatusReason reason, long proposedEpoch,
                    long expiresAfterEpoch);
    }

    @FunctionalInterface
    interface DRepDistributionConsumer {
        void accept(int drepType, byte[] drepHash, BigInteger coin);
    }
}
