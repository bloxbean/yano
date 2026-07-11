package com.bloxbean.cardano.yano.consensus.selection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardanoOrientedChainComparatorTest {
    private final CardanoOrientedChainComparator comparator = new CardanoOrientedChainComparator();

    @Test
    void higherBlockNumberWinsAsLongerChain() {
        ChainComparison comparison = comparator.compare(
                ChainCandidate.withoutDensity(candidate("a", 100, 10, "a")),
                ChainCandidate.withoutDensity(candidate("b", 101, 11, "b")));

        assertThat(comparison.winner()).isEqualTo(ChainComparison.Winner.B);
        assertThat(comparison.reason()).isEqualTo(ChainComparison.Reason.LONGER);
    }

    @Test
    void higherDensityWinsWhenBlockNumbersAreEqual() {
        ChainComparison comparison = comparator.compare(
                ChainCandidate.withDensity(candidate("a", 100, 10, "a"), 3),
                ChainCandidate.withDensity(candidate("b", 101, 10, "b"), 5));

        assertThat(comparison.winner()).isEqualTo(ChainComparison.Winner.B);
        assertThat(comparison.reason()).isEqualTo(ChainComparison.Reason.DENSER);
    }

    @Test
    void lowerValidatedVrfOutputWinsTieBreak() {
        CandidateHeader high = candidateWithVrf("a", 100, 10, "a", (byte) 9);
        CandidateHeader low = candidateWithVrf("b", 100, 10, "b", (byte) 1);

        ChainComparison comparison = comparator.compare(
                ChainCandidate.withDensity(high, 3),
                ChainCandidate.withDensity(low, 3));

        assertThat(comparison.winner()).isEqualTo(ChainComparison.Winner.B);
        assertThat(comparison.reason()).isEqualTo(ChainComparison.Reason.VRF_TIEBREAK);
    }

    @Test
    void missingVrfEvidenceUsesDeterministicFallback() {
        CandidateHeader a = candidate("a", 100, 10, "b-hash");
        CandidateHeader b = candidate("b", 101, 10, "a-hash");

        ChainComparison comparison = comparator.compare(
                ChainCandidate.withDensity(a, 3),
                ChainCandidate.withDensity(b, 3));

        assertThat(comparison.winner()).isEqualTo(ChainComparison.Winner.A);
        assertThat(comparison.reason()).isEqualTo(ChainComparison.Reason.FALLBACK);
    }

    private static CandidateHeader candidate(String peerId, long slot, long blockNumber, String hash) {
        return new CandidateHeader(peerId, slot, blockNumber, hash, "prev", false, slot);
    }

    private static CandidateHeader candidateWithVrf(String peerId,
                                                    long slot,
                                                    long blockNumber,
                                                    String hash,
                                                    byte fill) {
        byte[] vrf = new byte[64];
        java.util.Arrays.fill(vrf, fill);
        return new CandidateHeader(
                peerId,
                slot,
                blockNumber,
                hash,
                "prev",
                false,
                slot,
                "Babbage",
                HeaderValidationEvidence.accepted("praos-lite", List.of("structural", "vrf-proof")),
                vrf,
                false,
                false);
    }
}
