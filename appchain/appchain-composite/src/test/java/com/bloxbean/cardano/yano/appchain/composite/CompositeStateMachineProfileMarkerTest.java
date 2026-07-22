package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeStateMachineProfileMarkerTest {

    @Test
    void firstBlockCommitsCanonicalProfileAndReplayRequiresExactBytes() {
        CompositeProfile profile = CompositeProfile.of("empty", "1", List.of());
        CompositeStateMachine machine = CompositeStateMachine.forTest(
                profile, List.of(), List.of(), 1);
        MemoryState state = new MemoryState();

        machine.apply(block(1), state);
        assertThat(state.get(CompositeStateKeys.profileMarkerKey())).isPresent();
        assertThat(state.get(CompositeStateKeys.profileMarkerKey()).orElseThrow())
                .containsExactly(profile.canonicalBytes());

        machine.apply(block(2), state);
        machine.init(state, new AppChainInfo("chain", "00", 1));
    }

    @Test
    void retainedStateRejectsDifferentEffectiveProfile() {
        MemoryState state = new MemoryState();
        CompositeStateMachine original = CompositeStateMachine.forTest(
                CompositeProfile.of("empty", "1", List.of()), List.of(), List.of(), 1);
        original.apply(block(1), state);

        CompositeStateMachine changed = CompositeStateMachine.forTest(
                CompositeProfile.of("empty", "2", List.of()), List.of(), List.of(), 1);
        assertThatThrownBy(() -> changed.init(state, new AppChainInfo("chain", "00", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retained composite profile marker");
        assertThatThrownBy(() -> changed.apply(block(2), state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile marker does not match");
    }

    @Test
    void missingMarkerAfterGenesisFailsClosed() {
        CompositeStateMachine machine = CompositeStateMachine.forTest(
                CompositeProfile.of("empty", "1", List.of()), List.of(), List.of(), 1);

        assertThatThrownBy(() -> machine.apply(block(2), new MemoryState()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent after genesis");
    }

    @Test
    void retainedNonEmptyStateWithoutMarkerFailsDuringInit() {
        CompositeStateMachine machine = CompositeStateMachine.forTest(
                CompositeProfile.of("empty", "1", List.of()), List.of(), List.of(), 1);
        AppStateReader retained = new AppStateReader() {
            @Override
            public Optional<byte[]> get(byte[] key) {
                return Optional.empty();
            }

            @Override
            public byte[] stateRoot() {
                byte[] root = new byte[32];
                root[0] = 1;
                return root;
            }
        };

        assertThatThrownBy(() -> machine.init(
                retained, new AppChainInfo("chain", "00", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent from non-empty state");
    }

    @Test
    void physicalNamespaceIsBinaryCanonicalBoundedAndCollisionFree() {
        byte[] first = CompositeStateKeys.componentKey("a", new byte[]{0, 1, (byte) 0xff});
        byte[] second = CompositeStateKeys.componentKey("aa", new byte[]{1, (byte) 0xff});

        assertThat(first).isNotEqualTo(second);
        assertThat(first).hasSizeLessThanOrEqualTo(CompositeStateKeys.MAX_PHYSICAL_KEY_BYTES);
        assertThatThrownBy(() -> CompositeStateKeys.componentKey("a", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CompositeStateKeys.componentKey("a", new byte[240]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
        assertThat(CompositeStateKeys.componentKey("evidence", new byte[]{1, 2, 3}))
                .containsExactly(CompositeCommitmentV1.componentKey(
                        "evidence", new byte[]{1, 2, 3}));
        byte[] marker = CompositeStateKeys.profileMarkerKey();
        marker[0] ^= 1;
        assertThat(CompositeStateKeys.profileMarkerKey())
                .containsExactly("~composite/profile/v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static AppBlock block(long height) {
        return new AppBlock(1, "chain", height, new byte[32], 0, new byte[0],
                height, new byte[32], new byte[32], List.of(), new byte[32], FinalityCert.empty());
    }

    private static final class MemoryState implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public byte[] stateRoot() {
            return new byte[32];
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }
    }
}
