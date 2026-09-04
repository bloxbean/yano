package com.bloxbean.cardano.yano.api.appchain.consensus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsensusContextTest {

    @Test
    void validatesFourOfFiveWithOneByzantineMember() {
        ConsensusQuorum quorum = new ConsensusQuorum(5, 4, 1);

        assertThat(quorum.unavailableMembersTolerated()).isEqualTo(1);
    }

    @Test
    void rejectsNonIntersectingOrNonLiveProfiles() {
        assertThatThrownBy(() -> new ConsensusQuorum(5, 3, 1))
                .hasMessageContaining("intersect");
        assertThatThrownBy(() -> new ConsensusQuorum(5, 5, 1))
                .hasMessageContaining("live");
    }

    @Test
    void digestIsIndependentOfMemberInputOrderAndBindsObserverProfile() {
        byte[] a = filled(1);
        byte[] b = filled(2);
        ConsensusQuorum quorum = new ConsensusQuorum(2, 2, 0);
        ConsensusContext first = new ConsensusContext(3, "chain", filled(3), 7,
                quorum, List.of(a, b), filled(4), filled(5), filled(6));
        ConsensusContext reordered = new ConsensusContext(3, "chain", filled(3), 7,
                quorum, List.of(b, a), filled(4), filled(5), filled(6));
        ConsensusContext differentObserver = new ConsensusContext(3, "chain", filled(3), 7,
                quorum, List.of(a, b), filled(4), filled(7), filled(6));
        ConsensusContext differentObservation = new ConsensusContext(3, "chain", filled(3), 7,
                quorum, List.of(a, b), filled(4), filled(5), filled(7));

        assertThat(first.digest()).isEqualTo(reordered.digest());
        assertThat(first.digest()).isNotEqualTo(differentObserver.digest());
        assertThat(first.digest()).isNotEqualTo(differentObservation.digest());
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
