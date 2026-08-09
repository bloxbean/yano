package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Typed complete-only reads for the out-of-box epoch-governance component. */
public final class EpochGovernanceClient {
    private final AppChainClient client;

    public EpochGovernanceClient(AppChainClient client) { this.client = Objects.requireNonNull(client, "client"); }

    public Optional<ProposalAtHeight> proposal(long epoch, byte[] transactionId, int governanceActionIndex) {
        AppChainClient.QueryResult result = client.query(EpochGovernanceContract.PROPOSAL_QUERY_PATH,
                EpochGovernanceContract.encodeProposalQuery(new EpochGovernanceContract.ProposalQuery(
                        epoch, transactionId, governanceActionIndex)));
        if (result.payload().length == 0) return Optional.empty();
        return Optional.of(new ProposalAtHeight(epoch, transactionId, governanceActionIndex,
                EpochGovernanceContract.decodeProposalValue(result.payload()), result.committedHeight(), result.stateRoot()));
    }

    public Optional<DRepAtHeight> drep(long epoch, int drepType, byte[] drepHash) {
        AppChainClient.QueryResult result = client.query(EpochGovernanceContract.DREP_QUERY_PATH,
                EpochGovernanceContract.encodeDRepQuery(new EpochGovernanceContract.DRepQuery(epoch, drepType, drepHash)));
        if (result.payload().length == 0) return Optional.empty();
        return Optional.of(new DRepAtHeight(epoch, drepType, drepHash,
                EpochGovernanceContract.decodeCoin(result.payload()), result.committedHeight(), result.stateRoot()));
    }

    public Optional<ProposalCompletenessAtHeight> proposalCompleteness(long epoch) {
        AppChainClient.QueryResult result = client.query(EpochGovernanceContract.PROPOSAL_META_QUERY_PATH,
                EpochParamsContract.query(epoch));
        return result.payload().length == 0 ? Optional.empty() : Optional.of(new ProposalCompletenessAtHeight(
                EpochGovernanceContract.decodeProposalMeta(result.payload()), result.committedHeight(), result.stateRoot()));
    }

    public Optional<DRepCompletenessAtHeight> drepCompleteness(long epoch) {
        AppChainClient.QueryResult result = client.query(EpochGovernanceContract.DREP_META_QUERY_PATH,
                EpochParamsContract.query(epoch));
        return result.payload().length == 0 ? Optional.empty() : Optional.of(new DRepCompletenessAtHeight(
                EpochGovernanceContract.decodeDRepMeta(result.payload()), result.committedHeight(), result.stateRoot()));
    }

    public record ProposalAtHeight(long epoch, byte[] transactionId, int governanceActionIndex,
                                   EpochGovernanceContract.ProposalValue value,
                                   long committedHeight, byte[] stateRoot) {
        public ProposalAtHeight { transactionId = transactionId.clone(); stateRoot = stateRoot.clone(); }
        @Override public byte[] transactionId() { return transactionId.clone(); }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }
    public record DRepAtHeight(long epoch, int drepType, byte[] drepHash, BigInteger coin,
                               long committedHeight, byte[] stateRoot) {
        public DRepAtHeight { drepHash = drepHash.clone(); stateRoot = stateRoot.clone(); }
        @Override public byte[] drepHash() { return drepHash.clone(); }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }
    public record ProposalCompletenessAtHeight(EpochGovernanceContract.ProposalMeta meta,
                                               long committedHeight, byte[] stateRoot) {
        public ProposalCompletenessAtHeight { stateRoot = stateRoot.clone(); }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }
    public record DRepCompletenessAtHeight(EpochGovernanceContract.DRepMeta meta,
                                           long committedHeight, byte[] stateRoot) {
        public DRepCompletenessAtHeight { stateRoot = stateRoot.clone(); }
        @Override public byte[] stateRoot() { return stateRoot.clone(); }
    }
}
