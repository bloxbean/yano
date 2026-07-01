package com.bloxbean.cardano.yano.runtime.sync.multipeer;

import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationResult;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationSnapshot;
import com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiPeerScaffoldingTest {
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
    void headerFanInStoresCandidatesWithoutCanonicalState() {
        var store = new InMemoryCandidateHeaderStore();
        var fanIn = new HeaderFanIn(store);
        CandidateHeader header = candidate("peer-a", 101, "a", true);

        fanIn.onCandidateHeader(header);

        assertThat(store.get("a")).contains(header);
        assertThat(fanIn.candidatesAfter(100)).containsExactly(header);
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
    void candidateHeaderListenerStoresObservedHeadersOnlyAsCandidates() {
        var store = new InMemoryCandidateHeaderStore();
        var listener = new CandidateHeaderListener("peer-a", true, new HeaderFanIn(store), header -> { });

        listener.rollforward(null, BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .slot(100)
                        .blockNumber(10)
                        .prevHash("prev")
                        .blockHash("hash")
                        .build())
                .build(), new byte[] {1});

        assertThat(listener.headersObserved()).isEqualTo(1);
        assertThat(store.get("hash")).isPresent();
    }

    @Test
    void candidateHeaderListenerDoesNotStoreRejectedHeaders() {
        var store = new InMemoryCandidateHeaderStore();
        var listener = new CandidateHeaderListener(
                "peer-a",
                true,
                new HeaderFanIn(store),
                header -> { },
                rejectingHeaderValidator());

        listener.rollforward(null, BlockHeader.builder()
                .headerBody(HeaderBody.builder()
                        .slot(100)
                        .blockNumber(10)
                        .prevHash("prev")
                        .blockHash("hash")
                        .build())
                .build(), new byte[] {1});

        assertThat(listener.headersObserved()).isZero();
        assertThat(store.get("hash")).isEmpty();
    }

    private static CandidateHeader candidate(String peerId, long blockNumber, String hash, boolean trusted) {
        return new CandidateHeader(peerId, 1000 + blockNumber, blockNumber, hash, null, trusted, 1);
    }

    private static HeaderValidator rejectingHeaderValidator() {
        return new HeaderValidator() {
            @Override
            public HeaderValidationResult validateShelley(BlockHeader blockHeader, byte[] originalHeaderBytes) {
                return HeaderValidationResult.rejected("test", "header", "rejected by test");
            }

            @Override
            public HeaderValidationSnapshot snapshot() {
                return HeaderValidationSnapshot.none();
            }
        };
    }
}
