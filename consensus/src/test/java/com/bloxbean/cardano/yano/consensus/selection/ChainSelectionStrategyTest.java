package com.bloxbean.cardano.yano.consensus.selection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainSelectionStrategyTest {

    @Test
    void singleUntrustedLongerCandidateIsObserveOnly() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader candidate = candidate("peer-a", 101, "a", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(candidate)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.OBSERVE);
        assertThat(decision.selected()).isEqualTo(candidate);
    }

    @Test
    void trustedLongerCandidateCanBeAdopted() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader candidate = candidate("peer-a", 101, "a", true);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(candidate)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
    }

    @Test
    void untrustedQuorumCanBeAdopted() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader a = candidate("peer-a", 101, "same", false);
        CandidateHeader b = candidate("peer-b", 101, "same", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(a, b)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
    }

    @Test
    void trustedOnlyPolicyDoesNotAdoptUntrustedQuorum() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader a = candidate("peer-a", 101, "same", false);
        CandidateHeader b = candidate("peer-b", 101, "same", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                "trusted-only",
                List.of(a, b)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.OBSERVE);
    }

    @Test
    void validatedPolicyAdoptsCandidateWithAcceptedEvidence() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader candidate = candidateWithEvidence("peer-a", 101, "validated");

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                "validated",
                List.of(candidate)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
        assertThat(decision.reason()).contains("validation");
    }

    @Test
    void validatedPolicyDoesNotAdoptCandidateWithoutEvidence() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader candidate = candidate("peer-a", 101, "unvalidated", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                "validated",
                List.of(candidate)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.OBSERVE);
    }

    @Test
    void untrustedLongerCandidateDoesNotBlockTrustedCandidate() {
        var strategy = new TrustedOrQuorumCandidateWithinRollbackWindow();
        CandidateHeader trusted = candidate("trusted", 101, "trusted-hash", true);
        CandidateHeader untrusted = candidate("untrusted", 102, "untrusted-hash", false);

        ChainSelectionDecision decision = strategy.evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                List.of(trusted, untrusted)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
        assertThat(decision.selected()).isEqualTo(trusted);
    }

    @Test
    void candidateStoreKeepsSameHashObservationsPerPeerForQuorum() {
        var store = new InMemoryCandidateHeaderStore();
        var fanIn = new HeaderFanIn(store);
        CandidateHeader a = candidate("peer-a", 101, "same", false);
        CandidateHeader b = candidate("peer-b", 101, "same", false);

        fanIn.onCandidateHeader(a);
        fanIn.onCandidateHeader(b);

        assertThat(store.all()).containsExactlyInAnyOrder(a, b);

        var decision = new TrustedOrQuorumCandidateWithinRollbackWindow().evaluate(new ChainSelectionContext(
                100,
                1000,
                4320,
                2,
                fanIn.candidatesAfter(100)));

        assertThat(decision.action()).isEqualTo(ChainSelectionDecision.Action.ADOPT);
    }

    private static CandidateHeader candidate(String peerId, long blockNumber, String hash, boolean trusted) {
        return new CandidateHeader(peerId, 1000 + blockNumber, blockNumber, hash, null, trusted, 1);
    }

    private static CandidateHeader candidateWithEvidence(String peerId, long blockNumber, String hash) {
        return new CandidateHeader(
                peerId,
                1000 + blockNumber,
                blockNumber,
                hash,
                null,
                false,
                1,
                "shelley+",
                HeaderValidationEvidence.accepted("structural", List.of("structural")),
                null,
                false,
                false);
    }
}
