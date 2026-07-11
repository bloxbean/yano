package com.bloxbean.cardano.yano.consensus.selection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateHeaderStateTest {

    @Test
    void headerFanInStoresCandidatesWithoutCanonicalState() {
        var store = new InMemoryCandidateHeaderStore();
        var fanIn = new HeaderFanIn(store);
        CandidateHeader header = candidate("peer-a", 101, "a", true);

        fanIn.onCandidateHeader(header);

        assertThat(store.get("a")).contains(header);
        assertThat(fanIn.candidatesAfter(100)).containsExactly(header);
    }

    @Test
    void candidateStoreDeduplicatesSamePeerSameHash() {
        var store = new InMemoryCandidateHeaderStore();
        CandidateHeader older = new CandidateHeader("peer-a", 1100, 100, "same", null, false, 1);
        CandidateHeader newer = new CandidateHeader("peer-a", 1101, 100, "same", null, false, 2);

        store.put(older);
        store.put(newer);

        assertThat(store.all()).containsExactly(newer);
        assertThat(store.get("same")).contains(newer);
    }

    @Test
    void candidateStoreEvictsOldestObservationsWhenBounded() {
        var store = new InMemoryCandidateHeaderStore(2);
        CandidateHeader old = new CandidateHeader("peer-a", 1000, 100, "old", null, false, 1);
        CandidateHeader middle = new CandidateHeader("peer-b", 1001, 101, "middle", null, false, 2);
        CandidateHeader newest = new CandidateHeader("peer-c", 1002, 102, "newest", null, false, 3);

        store.put(old);
        store.put(middle);
        store.put(newest);

        assertThat(store.all()).containsExactly(middle, newest);
        assertThat(store.get("old")).isEmpty();
    }

    @Test
    void candidateHeaderCarriesValidationEvidenceAndDefensiveVrfOutput() {
        byte[] vrf = new byte[] {3, 2, 1};
        var evidence = HeaderValidationEvidence.accepted("header-signature",
                java.util.List.of("structural", "kes-signature", "opcert-signature"));
        CandidateHeader header = new CandidateHeader(
                "peer-a",
                1000,
                100,
                "hash",
                "prev",
                false,
                1,
                "Babbage",
                evidence,
                vrf,
                true,
                true);

        vrf[0] = 9;
        byte[] returned = header.vrfOutput();
        returned[1] = 9;

        assertThat(header.validationEvidence().producesEvidence()).isTrue();
        assertThat(header.validationEvidence().includesStage("kes-signature")).isTrue();
        assertThat(header.era()).isEqualTo("Babbage");
        assertThat(header.vrfOutput()).containsExactly(3, 2, 1);
        assertThat(header.bodyValidated()).isTrue();
    }

    @Test
    void rejectedValidationEvidenceRecordsStageAndReason() {
        var evidence = HeaderValidationEvidence.rejected(
                "structural",
                java.util.List.of("shape"),
                "header-hash",
                "hash mismatch");

        assertThat(evidence.accepted()).isFalse();
        assertThat(evidence.acceptedStages()).containsExactly("shape");
        assertThat(evidence.rejectedStage()).isEqualTo("header-hash");
        assertThat(evidence.rejectionReason()).isEqualTo("hash mismatch");
    }

    private static CandidateHeader candidate(String peerId, long blockNumber, String hash, boolean trusted) {
        return new CandidateHeader(peerId, 1000 + blockNumber, blockNumber, hash, null, trusted, 1);
    }
}
