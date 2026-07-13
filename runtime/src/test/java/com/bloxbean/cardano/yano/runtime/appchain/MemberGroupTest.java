package com.bloxbean.cardano.yano.runtime.appchain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave-4 review fix: height-versioned membership — historical blocks verify
 * against the epoch in effect at their height, so retiring a key never
 * invalidates finalized history.
 */
class MemberGroupTest {

    @Test
    void epochs_historicalVerification_and_persistenceRoundTrip() {
        MemberGroup group = new MemberGroup(Set.of("aa", "bb", "cc"), 2);

        // Blocks 1..100 finalized under {aa,bb,cc}@2; then cc retired from 101
        group.appendEpoch(101, Set.of("aa", "bb"), 2);

        // History: cc still counts for heights <= 100, not after
        assertThat(group.containsAt("cc", 50)).isTrue();
        assertThat(group.containsAt("cc", 100)).isTrue();
        assertThat(group.containsAt("cc", 101)).isFalse();
        assertThat(group.contains("cc")).isFalse();          // current epoch
        assertThat(group.thresholdAt(50)).isEqualTo(2);
        assertThat(group.membersAt(101)).containsExactlyInAnyOrder("aa", "bb");

        // Several admin steps before the next block replace the same epoch
        group.appendEpoch(101, Set.of("aa", "bb", "dd"), 3);
        assertThat(group.history()).hasSize(2);
        assertThat(group.thresholdAt(101)).isEqualTo(3);
        assertThat(group.containsAt("cc", 99)).isTrue();     // history intact

        // Persistence round-trip preserves the full history
        String encoded = group.encode();
        MemberGroup restored = new MemberGroup(Set.of("zz"), 1);
        restored.load(MemberGroup.decode(encoded));
        assertThat(restored.containsAt("cc", 100)).isTrue();
        assertThat(restored.containsAt("cc", 101)).isFalse();
        assertThat(restored.membersAt(200)).containsExactlyInAnyOrder("aa", "bb", "dd");
        assertThat(restored.threshold()).isEqualTo(3);
        assertThat(restored.history()).hasSize(2);

        // Case-insensitive lookups
        assertThat(restored.containsAt("CC", 100)).isTrue();

        List<MemberGroup.Epoch> history = restored.history();
        assertThat(history.get(0).fromHeight()).isZero();
        assertThat(history.get(1).fromHeight()).isEqualTo(101);
    }
}
