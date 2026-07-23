package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave-4 review fix: height-versioned membership — historical blocks verify
 * against the epoch in effect at their height, so retiring a key never
 * invalidates finalized history.
 */
class MemberGroupTest {
    private static final String KEY_A = key(10);
    private static final String KEY_B = key(11);
    private static final String KEY_C = key(12);
    private static final String KEY_D = key(13);
    private static final String KEY_Z = key(35);

    @Test
    void epochs_historicalVerification_and_persistenceRoundTrip() {
        MemberGroup group = new MemberGroup(Set.of(KEY_A, KEY_B, KEY_C), 2);

        // Blocks 1..100 finalized under {aa,bb,cc}@2; then cc retired from 101
        group.appendEpoch(101, Set.of(KEY_A, KEY_B), 2);

        // History: cc still counts for heights <= 100, not after
        assertThat(group.containsAt(KEY_C, 50)).isTrue();
        assertThat(group.containsAt(KEY_C, 100)).isTrue();
        assertThat(group.containsAt(KEY_C, 101)).isFalse();
        assertThat(group.contains(KEY_C)).isFalse();          // current epoch
        assertThat(group.thresholdAt(50)).isEqualTo(2);
        assertThat(group.membersAt(101)).containsExactlyInAnyOrder(KEY_A, KEY_B);

        // Several admin steps before the next block replace the same epoch
        group.appendEpoch(101, Set.of(KEY_A, KEY_B, KEY_D), 3);
        assertThat(group.history()).hasSize(2);
        assertThat(group.thresholdAt(101)).isEqualTo(3);
        assertThat(group.containsAt(KEY_C, 99)).isTrue();     // history intact

        // Persistence round-trip preserves the full history
        String encoded = group.encode();
        MemberGroup restored = new MemberGroup(Set.of(KEY_Z), 1);
        restored.load(MemberGroup.decode(encoded));
        assertThat(restored.containsAt(KEY_C, 100)).isTrue();
        assertThat(restored.containsAt(KEY_C, 101)).isFalse();
        assertThat(restored.membersAt(200)).containsExactlyInAnyOrder(KEY_A, KEY_B, KEY_D);
        assertThat(restored.threshold()).isEqualTo(3);
        assertThat(restored.history()).hasSize(2);

        // Case-insensitive lookups
        assertThat(restored.containsAt(KEY_C.toUpperCase(), 100)).isTrue();

        List<MemberGroup.Epoch> history = restored.history();
        assertThat(history.get(0).fromHeight()).isZero();
        assertThat(history.get(1).fromHeight()).isEqualTo(101);
    }

    @Test
    void rejectsAnOversizedMembershipEpochAtEveryEntryPoint() {
        Set<String> oversized = new LinkedHashSet<>();
        for (int index = 0; index <= AppChainConfig.MAX_MEMBERS; index++) {
            oversized.add(String.format("%064x", index));
        }

        assertThatThrownBy(() -> new MemberGroup(oversized, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v1 profile");

        MemberGroup group = new MemberGroup(Set.of(KEY_A), 1);
        assertThatThrownBy(() -> group.appendEpoch(1, oversized, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v1 profile");
        assertThat(group.members()).containsExactly(KEY_A);
    }

    @Test
    void rejectsInvalidThresholdKeysAndPersistedEpochOrdering() {
        assertThatThrownBy(() -> new MemberGroup(Set.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberGroup(Set.of(KEY_A), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberGroup(Set.of(KEY_A), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberGroup(Set.of("aa"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberGroup.decode("0|2|" + KEY_A))
                .isInstanceOf(IllegalArgumentException.class);

        MemberGroup group = new MemberGroup(Set.of(KEY_A), 1);
        assertThatThrownBy(() -> group.load(List.of(
                new MemberGroup.Epoch(1, Set.of(KEY_A), 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start at 0");
        assertThatThrownBy(() -> group.load(List.of(
                new MemberGroup.Epoch(0, Set.of(KEY_A), 1),
                new MemberGroup.Epoch(2, Set.of(KEY_A), 1),
                new MemberGroup.Epoch(1, Set.of(KEY_A), 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly ordered");
    }

    @Test
    void governedAdditionBeyondTheV1LimitIsDeterministicallyVoid() {
        Set<String> members = new LinkedHashSet<>();
        for (int index = 0; index < AppChainConfig.MAX_MEMBERS; index++) {
            members.add(String.format("%064x", index));
        }
        MemberGroup group = new MemberGroup(members, 1);
        GovernedMembership governed = new GovernedMembership(group, "", 100,
                LoggerFactory.getLogger(MemberGroupTest.class));
        String approver = members.iterator().next();
        byte[] command = GovernedMembership.encodeCommand(GovernedMembership.OP_ADD,
                HexUtil.decodeHexString("ff".repeat(32)), 0, 1);
        AppMessage message = AppMessage.builder()
                .version(AppMessage.ENVELOPE_VERSION)
                .messageId(new byte[32])
                .chainId("bounded-chain")
                .topic(GovernedMembership.TOPIC)
                .sender(HexUtil.decodeHexString(approver))
                .senderSeq(1)
                .expiresAt(1)
                .body(command)
                .authScheme(FinalityCert.SCHEME_ED25519)
                .authProof(new byte[]{1})
                .build();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "bounded-chain", 1,
                AppBlock.GENESIS_PREV_HASH, 0, new byte[0], 1,
                new byte[32], new byte[32], List.of(message), new byte[32],
                FinalityCert.empty());

        GovernedMembership.Result result = governed.processBlock(block);

        assertThat(result.effects()).isEmpty();
        assertThat(group.members()).hasSize(AppChainConfig.MAX_MEMBERS);
        assertThat(group.members()).doesNotContain("ff".repeat(32));
    }

    private static String key(int value) {
        return String.format("%064x", value);
    }
}
