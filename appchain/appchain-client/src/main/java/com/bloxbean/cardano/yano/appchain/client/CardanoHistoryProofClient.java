package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** Fetches exact-height typed proof pairs for a composed Cardano History chain. */
public final class CardanoHistoryProofClient {
    private final AppChainClient client;
    private final String paramsComponent;
    private final String stakeComponent;
    private final String governanceComponent;

    public CardanoHistoryProofClient(AppChainClient client, String paramsComponent,
                                     String stakeComponent, String governanceComponent) {
        this.client = Objects.requireNonNull(client, "client");
        this.paramsComponent = required(paramsComponent);
        this.stakeComponent = required(stakeComponent);
        this.governanceComponent = required(governanceComponent);
    }

    public Optional<CardanoHistoryProofBundle.ProtocolParameters> protocolParameters(
            long epoch, long anchoredHeight) {
        return client.proof(ProofSubjects.epochProtocolParameters(paramsComponent, epoch), anchoredHeight)
                .map(proof -> new CardanoHistoryProofBundle.ProtocolParameters(epoch, paramsComponent, proof));
    }

    public Optional<CardanoHistoryProofBundle.Stake> stake(
            long epoch, int credentialType, byte[] credentialHash,
            CardanoHistoryProofBundle.StakeMode mode, BigInteger coin,
            byte[] poolHash, long anchoredHeight) {
        var fact = client.proof(ProofSubjects.epochStake(
                stakeComponent, epoch, credentialType, credentialHash), anchoredHeight);
        var complete = client.proof(ProofSubjects.epochStakeCompleteness(
                stakeComponent, epoch), anchoredHeight);
        if (fact.isEmpty() || complete.isEmpty()) return Optional.empty();
        return Optional.of(new CardanoHistoryProofBundle.Stake(epoch, stakeComponent,
                credentialType, credentialHash, mode, coin, poolHash,
                fact.orElseThrow(), complete.orElseThrow()));
    }

    public Optional<CardanoHistoryProofBundle.Proposal> proposal(
            long epoch, byte[] transactionId, int governanceActionIndex,
            EpochGovernanceContract.ActionType actionType,
            EpochGovernanceContract.ProposalStatus status,
            EpochGovernanceContract.ProposalReason reason, long anchoredHeight) {
        var fact = client.proof(ProofSubjects.governanceProposal(governanceComponent,
                epoch, transactionId, governanceActionIndex), anchoredHeight);
        var complete = client.proof(ProofSubjects.governanceProposalCompleteness(
                governanceComponent, epoch), anchoredHeight);
        if (fact.isEmpty() || complete.isEmpty()) return Optional.empty();
        return Optional.of(new CardanoHistoryProofBundle.Proposal(epoch, governanceComponent,
                transactionId, governanceActionIndex, actionType, status, reason,
                fact.orElseThrow(), complete.orElseThrow()));
    }

    public Optional<CardanoHistoryProofBundle.DRepAmount> drepAmount(
            long epoch, int drepType, byte[] drepHash,
            CardanoHistoryProofBundle.AmountMode mode, BigInteger coin,
            long anchoredHeight) {
        var fact = client.proof(ProofSubjects.drepDistribution(
                governanceComponent, epoch, drepType, drepHash), anchoredHeight);
        var complete = client.proof(ProofSubjects.drepDistributionCompleteness(
                governanceComponent, epoch), anchoredHeight);
        if (fact.isEmpty() || complete.isEmpty()) return Optional.empty();
        return Optional.of(new CardanoHistoryProofBundle.DRepAmount(epoch, governanceComponent,
                drepType, drepHash, mode, coin, fact.orElseThrow(), complete.orElseThrow()));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("component id is required");
        return value;
    }
}
