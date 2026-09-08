package com.bloxbean.cardano.yano.api.appchain.consensus;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CertifiedConsensusSafetyTest {

    @Test
    void fourOfFiveQuorumsIntersectInAtLeastThreeMembers() {
        Set<Integer> first = Set.of(0, 1, 2, 3);
        Set<Integer> competing = Set.of(1, 2, 3, 4);
        Set<Integer> intersection = new HashSet<>(first);
        intersection.retainAll(competing);

        ConsensusQuorum quorum = new ConsensusQuorum(5, 4, 1);
        assertThat(intersection).hasSize(3);
        assertThat(intersection.size()).isGreaterThan(quorum.maxByzantineMembers());
        assertThat(quorum.unavailableMembersTolerated()).isEqualTo(1);
    }

    @Test
    void oneUnavailableMemberProgressesButTwoCannotReachQuorum() {
        ConsensusQuorum quorum = new ConsensusQuorum(5, 4, 1);
        assertThat(5 - 1).isGreaterThanOrEqualTo(quorum.threshold());
        assertThat(5 - 2).isLessThan(quorum.threshold());
    }

    @Test
    void threeTwoPartitionCannotPrepareOrCommitEitherSide() {
        ConsensusQuorum quorum = new ConsensusQuorum(5, 4, 1);
        assertThat(3).isLessThan(quorum.threshold());
        assertThat(2).isLessThan(quorum.threshold());
    }
}
