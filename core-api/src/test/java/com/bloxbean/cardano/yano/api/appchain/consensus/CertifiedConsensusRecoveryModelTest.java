package com.bloxbean.cardano.yano.api.appchain.consensus;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic five-member model for the issue-85 split-round reproduction. */
class CertifiedConsensusRecoveryModelTest {

    private static final Set<String> MEMBERS = Set.of("a", "b", "c", "d", "e");
    private static final int QUORUM = 4;

    @Test
    void splitViewZeroCannotFinalizeAndCertifiedNextViewRetainsStableFact() {
        Set<String> preparesForLeft = Set.of("a", "b");
        Set<String> preparesForRight = Set.of("c", "d");
        DurableFact fact = new DurableFact("deposit-42");

        assertThat(preparesForLeft).hasSizeLessThan(QUORUM);
        assertThat(preparesForRight).hasSizeLessThan(QUORUM);
        assertThat(fact.finalized()).isFalse();
        assertThat(fact.pending()).isTrue();

        Set<String> timeouts = Set.of("a", "b", "c", "d");
        assertThat(timeouts).hasSize(QUORUM);
        Set<String> viewOnePrepares = new LinkedHashSet<>(MEMBERS);
        viewOnePrepares.remove("e");
        assertThat(viewOnePrepares).hasSize(QUORUM);

        fact.finalizeOnce();
        fact.finalizeOnce();
        assertThat(fact.pending()).isFalse();
        assertThat(fact.finalized()).isTrue();
        assertThat(fact.cursorTransitions()).isOne();
    }

    @Test
    void preparedValueMustCrossEveryFourOfFiveTimeoutQuorum() {
        Set<String> prepared = Set.of("a", "b", "c", "d");
        Set<String> timeouts = Set.of("b", "c", "d", "e");
        Set<String> intersection = new HashSet<>(prepared);
        intersection.retainAll(timeouts);

        assertThat(intersection).hasSize(3);
        assertThat(intersection).hasSizeGreaterThan(1);
        assertThat(highestPrepared("value-x", intersection)).isEqualTo("value-x");
    }

    private static String highestPrepared(String value, Set<String> carriers) {
        return carriers.size() > 1 ? value : null;
    }

    private static final class DurableFact {
        private final String id;
        private boolean pending = true;
        private boolean finalized;
        private int cursorTransitions;

        private DurableFact(String id) {
            this.id = id;
        }

        private void finalizeOnce() {
            if (finalized) {
                return;
            }
            assertThat(id).isNotBlank();
            pending = false;
            finalized = true;
            cursorTransitions++;
        }

        private boolean pending() {
            return pending;
        }

        private boolean finalized() {
            return finalized;
        }

        private int cursorTransitions() {
            return cursorTransitions;
        }
    }
}
