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

    public Optional<CardanoHistoryProofBundle.SnapshotStake> stake(
            long epoch, int credentialType, byte[] credentialHash,
            CardanoHistoryProofBundle.StakeMode mode, BigInteger coin,
            byte[] poolHash, long anchoredHeight) {
        String series = stakeComponent + ".distribution";
        var descriptor = snapshotForEpoch(series, epoch, anchoredHeight, true);
        if (descriptor.isEmpty()) return Optional.empty();
        byte[] key = EpochStakeContract.credentialOrderKey(credentialType, credentialHash);
        var proof = client.authenticatedSnapshotProof(series,
                descriptor.orElseThrow().descriptor().sequence(), key, anchoredHeight);
        if (proof.isEmpty()) return Optional.empty();
        requireAnchorHeight(proof.orElseThrow(), anchoredHeight);
        return Optional.of(new CardanoHistoryProofBundle.SnapshotStake(epoch, series,
                credentialType, credentialHash, mode, coin, poolHash, proof.orElseThrow()));
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

    public Optional<CardanoHistoryProofBundle.SnapshotDRepAmount> drepAmount(
            long epoch, int drepType, byte[] drepHash,
            CardanoHistoryProofBundle.AmountMode mode, BigInteger coin,
            long anchoredHeight) {
        String series = governanceComponent + ".drep-distribution";
        var descriptor = snapshotForEpoch(series, epoch, anchoredHeight, false);
        if (descriptor.isEmpty()) return Optional.empty();
        byte[] key = EpochGovernanceContract.drepOrderKey(drepType, drepHash);
        var proof = client.authenticatedSnapshotProof(series,
                descriptor.orElseThrow().descriptor().sequence(), key, anchoredHeight);
        if (proof.isEmpty()) return Optional.empty();
        requireAnchorHeight(proof.orElseThrow(), anchoredHeight);
        return Optional.of(new CardanoHistoryProofBundle.SnapshotDRepAmount(epoch, series,
                drepType, drepHash, mode, coin, proof.orElseThrow()));
    }

    private Optional<AppChainClient.AuthenticatedSnapshotDescriptor> snapshotForEpoch(
            String series, long epoch, long anchoredHeight, boolean stake) {
        String cursor = null;
        AppChainClient.AuthenticatedSnapshotDescriptor newest = null;
        for (int pageIndex = 0; pageIndex < 10_000; pageIndex++) {
            AppChainClient.AuthenticatedSnapshotPage page =
                    client.authenticatedSnapshots(series, cursor, 100);
            for (var summary : page.items()) {
                var candidate = client.authenticatedSnapshot(series, summary.sequence());
                if (candidate.isPresent()) {
                    var value = candidate.orElseThrow();
                    var descriptor = value.descriptor();
                    boolean semanticMatch = descriptor.complete()
                            && descriptor.completedAppChainHeight() <= anchoredHeight
                            && "blake2b256".equals(descriptor.sourceCommitmentAlgorithm())
                            && descriptor.sourceBoundary()
                            instanceof com.bloxbean.cardano.yano.api.appchain.snapshot
                            .SnapshotSourceBoundary.L1Epoch boundary
                            && boundary.datasetEpoch() == epoch
                            && (stake
                            ? "epoch-stake-v1".equals(descriptor.schemaId())
                            && "epoch-stake-source-v1".equals(
                            descriptor.sourceCommitmentWireVersion())
                            && boundary.previousEpoch() == epoch && epoch != Long.MAX_VALUE
                            && boundary.newEpoch() == epoch + 1
                            : "epoch-drep-distribution-v1".equals(descriptor.schemaId())
                            && "epoch-drep-source-v1".equals(
                            descriptor.sourceCommitmentWireVersion())
                            && boundary.newEpoch() == epoch
                            && boundary.previousEpoch() == (epoch == 0 ? 0 : epoch - 1));
                    if (semanticMatch && (newest == null
                            || descriptor.sequence() > newest.descriptor().sequence())) {
                        newest = value;
                    }
                }
            }
            cursor = page.nextCursor();
            if (cursor == null) return Optional.ofNullable(newest);
        }
        throw new AppChainClient.AppChainClientException(
                "Authenticated snapshot catalog exceeds safe traversal bound");
    }

    private static void requireAnchorHeight(AppChainClient.AuthenticatedSnapshotProof proof,
                                            long anchoredHeight) {
        if (proof.anchor().anchoredHeight() != anchoredHeight) {
            throw new AppChainClient.AppChainClientException(
                    "Authenticated snapshot proof was served for a different anchored height");
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("component id is required");
        return value;
    }
}
