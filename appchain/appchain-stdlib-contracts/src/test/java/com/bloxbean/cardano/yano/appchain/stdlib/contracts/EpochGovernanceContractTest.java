package com.bloxbean.cardano.yano.appchain.stdlib.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochGovernanceContractTest {
    @Test
    void roundTripsEveryClaimMetadataQueryAndValue() {
        var proposal = new EpochGovernanceContract.Proposal(170, bytes(32, 1), 2,
                EpochGovernanceContract.ActionType.UPDATE_COMMITTEE,
                EpochGovernanceContract.ProposalStatus.DROPPED,
                EpochGovernanceContract.ProposalReason.SUPERSEDED, 168, 174);
        var dreps = List.of(drep(0), drep(1));
        byte[] proposalRoot = EpochGovernanceContract.proposalRoot(
                List.of(EpochGovernanceContract.proposalHash(proposal)));
        byte[] drepRoot = EpochGovernanceContract.drepRoot(
                List.of(EpochGovernanceContract.drepChunkHash(dreps)));
        var header = new EpochGovernanceContract.Header(170, true, 1, proposalRoot,
                true, 2, 2, 1, drepRoot);
        var chunk = new EpochGovernanceContract.DRepChunk(170, drepRoot, 0, dreps);

        assertThat(EpochGovernanceContract.decodeHeader(
                EpochGovernanceContract.encodeHeader(header))).isEqualTo(header);
        assertThat(EpochGovernanceContract.decodeProposal(
                EpochGovernanceContract.encodeProposal(proposal))).isEqualTo(proposal);
        assertThat(EpochGovernanceContract.decodeDRepChunk(
                EpochGovernanceContract.encodeDRepChunk(chunk)).entries()).isEqualTo(dreps);
        assertThat(EpochGovernanceContract.decodeProposalValue(
                EpochGovernanceContract.encodeProposalValue(proposal)).status())
                .isEqualTo(EpochGovernanceContract.ProposalStatus.DROPPED);
        assertThat(EpochGovernanceContract.decodeCoin(
                EpochGovernanceContract.encodeCoin(BigInteger.valueOf(42))))
                .isEqualTo(BigInteger.valueOf(42));

        var proposalMeta = new EpochGovernanceContract.ProposalMeta(
                170, 1, proposalRoot, 1, true);
        var drepMeta = new EpochGovernanceContract.DRepMeta(
                170, 2, 2, 1, drepRoot, 1, true);
        assertThat(EpochGovernanceContract.decodeProposalMeta(
                EpochGovernanceContract.encodeProposalMeta(proposalMeta))).isEqualTo(proposalMeta);
        assertThat(EpochGovernanceContract.decodeDRepMeta(
                EpochGovernanceContract.encodeDRepMeta(drepMeta))).isEqualTo(drepMeta);
    }

    @Test
    void rejectsNonCanonicalOrderMalformedTagsAndOversizedIdentityFields() {
        assertThatThrownBy(() -> new EpochGovernanceContract.DRepChunk(
                170, new byte[32], 0, List.of(drep(1), drep(0))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");
        byte[] proposal = EpochGovernanceContract.encodeProposal(new EpochGovernanceContract.Proposal(
                170, bytes(32, 1), 0, EpochGovernanceContract.ActionType.INFO_ACTION,
                EpochGovernanceContract.ProposalStatus.ACTIVE,
                EpochGovernanceContract.ProposalReason.NONE, 169, 175));
        proposal[1] = 2;
        assertThatThrownBy(() -> EpochGovernanceContract.decodeProposal(proposal))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpochGovernanceContract.ProposalQuery(170, new byte[31], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void physicalProofKeysAndValuesFitReleasedMpfEnvelope() {
        var proposal = new EpochGovernanceContract.Proposal(170, bytes(32, 1), 65_535,
                EpochGovernanceContract.ActionType.NEW_CONSTITUTION,
                EpochGovernanceContract.ProposalStatus.ENACTED,
                EpochGovernanceContract.ProposalReason.ENACTED, 160, 180);
        assertThat(EpochGovernanceContract.proposalKey(170, proposal.transactionId(),
                proposal.governanceActionIndex()).length).isLessThanOrEqualTo(256);
        assertThat(EpochGovernanceContract.drepKey(170, 1, bytes(28, 2)).length)
                .isLessThanOrEqualTo(256);
        assertThat(EpochGovernanceContract.encodeProposalValue(proposal).length)
                .isLessThanOrEqualTo(8 * 1024);
        assertThat(EpochGovernanceContract.encodeCoin(BigInteger.valueOf(Long.MAX_VALUE)).length)
                .isLessThanOrEqualTo(8 * 1024);
    }

    private static EpochGovernanceContract.DRepEntry drep(int suffix) {
        return new EpochGovernanceContract.DRepEntry(0, bytes(28, suffix),
                BigInteger.valueOf(1_000_000 + suffix));
    }

    private static byte[] bytes(int size, int suffix) {
        byte[] value = new byte[size];
        Arrays.fill(value, (byte) suffix);
        return value;
    }
}
