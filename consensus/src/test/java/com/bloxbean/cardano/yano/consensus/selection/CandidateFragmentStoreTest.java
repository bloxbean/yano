package com.bloxbean.cardano.yano.consensus.selection;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateFragmentStoreTest {
    @Test
    void buildsContinuousFragmentEndingAtTip() {
        var store = new InMemoryCandidateFragmentStore();
        CandidateHeader a = candidate("peer-a", 100, 10, "a", "canonical");
        CandidateHeader b = candidate("peer-a", 101, 11, "b", "a");

        store.put(a);
        store.put(b);

        CandidateFragment fragment = store.fragmentEndingAt("peer-a", "b").orElseThrow();

        assertThat(fragment.headers()).containsExactly(a, b);
        assertThat(fragment.tip()).isEqualTo(b);
        assertThat(fragment.blocksInWindow(101)).isEqualTo(1);
    }

    @Test
    void disconnectedLongerHeaderDoesNotIntersectCanonical() {
        var store = new InMemoryCandidateFragmentStore();
        CandidateHeader header = candidate("peer-a", 110, 20, "candidate", "missing");
        store.put(header);

        CandidateFragment fragment = store.fragmentEndingAt("peer-a", "candidate").orElseThrow();

        assertThat(fragment.findIntersection(canonical("canonical", 100), 110, 20)).isEmpty();
    }

    @Test
    void fragmentIntersectsCanonicalWithinRollbackWindow() {
        var store = new InMemoryCandidateFragmentStore();
        CandidateHeader a = candidate("peer-a", 101, 11, "a", "canonical");
        CandidateHeader b = candidate("peer-a", 102, 12, "b", "a");
        store.put(a);
        store.put(b);

        CandidateFragment fragment = store.fragmentEndingAt("peer-a", "b").orElseThrow();

        assertThat(fragment.findIntersection(canonical("canonical", 100), 120, 20))
                .contains(new CanonicalHeaderPoint(100, 10, "canonical"));
        assertThat(fragment.findIntersection(canonical("canonical", 100), 121, 20))
                .isEmpty();
    }

    @Test
    void storeReturnsOnlyTipFragmentsAheadOfBlockNumber() {
        var store = new InMemoryCandidateFragmentStore();
        CandidateHeader a = candidate("peer-a", 101, 11, "a", "canonical");
        CandidateHeader b = candidate("peer-a", 102, 12, "b", "a");
        CandidateHeader c = candidate("peer-b", 103, 9, "c", "canonical");
        store.put(a);
        store.put(b);
        store.put(c);

        assertThat(store.fragmentsAfter(10))
                .extracting(fragment -> fragment.tip().blockHash())
                .containsExactly("b");
    }

    @Test
    void storePrunesAndEvictsOldestHeaders() {
        var store = new InMemoryCandidateFragmentStore(2);
        CandidateHeader old = candidate("peer-a", 100, 10, "old", "canonical");
        CandidateHeader middle = candidate("peer-a", 101, 11, "middle", "old");
        CandidateHeader newest = candidate("peer-a", 102, 12, "newest", "middle");

        store.put(old);
        store.put(middle);
        store.put(newest);

        assertThat(store.fragmentEndingAt("peer-a", "old")).isEmpty();
        assertThat(store.fragmentEndingAt("peer-a", "newest").orElseThrow().headers())
                .containsExactly(middle, newest);

        store.pruneBeforeSlot(102);

        assertThat(store.fragmentEndingAt("peer-a", "middle")).isEmpty();
        assertThat(store.fragmentEndingAt("peer-a", "newest")).isPresent();
    }

    private static CanonicalChainView canonical(String hash, long slot) {
        Map<String, CanonicalHeaderPoint> points = Map.of(hash, new CanonicalHeaderPoint(slot, 10, hash));
        return blockHash -> Optional.ofNullable(points.get(blockHash));
    }

    private static CandidateHeader candidate(String peerId,
                                             long slot,
                                             long blockNumber,
                                             String hash,
                                             String previousHash) {
        return new CandidateHeader(peerId, slot, blockNumber, hash, previousHash, false, slot);
    }
}
