package org.yanoproject.api.appchain.transition;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateWriter;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionWorkAccountingTest {
    private static final byte[] KEY = {1};
    private static final TransitionWorkBudget BUDGET = new TransitionWorkBudget("crypto", KEY, 3);

    @Test
    void declarationAndRequestBoundsAreDefensiveAndContentBased() {
        byte[] key = {1};
        TransitionWorkBudget budget = new TransitionWorkBudget("crypto", key, 3);
        key[0] = 2;
        budget.key()[0] = 3;
        assertThat(budget).isEqualTo(BUDGET).hasSameHashCodeAs(BUDGET);
        assertThatThrownBy(() -> new TransitionWorkBudget("bad/id", KEY, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionWorkBudget("crypto", new byte[257], 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionWorkBudget("crypto", new byte[0], 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionWorkBudget("crypto", KEY, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionWorkReference("actors/other", "crypto"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionWorkRequest(new TransitionWorkReference("actors", "crypto"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesLegacyWireAndSharesReservationsAcrossCallersWithoutRefund() {
        MemoryState state = new MemoryState();
        assertThat(TransitionWorkAccounting.reserve(state, BUDGET, 7, 2)).isTrue();
        assertThat(HexFormat.of().formatHex(state.get(KEY).orElseThrow()))
                .isEqualTo("000000000000000700000002");
        assertThat(TransitionWorkAccounting.reserve(state, BUDGET, 7, 2)).isFalse();
        assertThat(state.writes).isEqualTo(1);
        assertThat(TransitionWorkAccounting.reserve(state, BUDGET, 7, 1)).isTrue();
        assertThat(TransitionWorkAccounting.reserve(state, BUDGET, 7, 1)).isFalse();
        assertThat(TransitionWorkAccounting.reserve(state, BUDGET, 8, 3)).isTrue();
        assertThat(HexFormat.of().formatHex(state.get(KEY).orElseThrow()))
                .isEqualTo("000000000000000800000003");
    }

    @Test
    void rejectsCorruptionAndBackwardHeightInsteadOfMaskingAsCapacity() {
        MemoryState state = new MemoryState();
        for (byte[] invalid : new byte[][]{new byte[11], encoded(0, 1), encoded(1, -1), encoded(1, 4)}) {
            state.put(KEY, invalid);
            assertThatThrownBy(() -> TransitionWorkAccounting.reserve(state, BUDGET, 2, 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("corrupt");
        }
        state.put(KEY, encoded(3, 1));
        assertThatThrownBy(() -> TransitionWorkAccounting.reserve(state, BUDGET, 2, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("backwards");
        assertThatThrownBy(() -> TransitionWorkAccounting.reserve(state, BUDGET, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransitionWorkAccounting.reserve(state, BUDGET, 2, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void integerMaximumCannotOverflowAndZeroChargeRetainsExistingSemantics() {
        MemoryState state = new MemoryState();
        TransitionWorkBudget maximum = new TransitionWorkBudget("maximum", KEY, Integer.MAX_VALUE);
        assertThat(TransitionWorkAccounting.reserve(state, maximum, Long.MAX_VALUE, Integer.MAX_VALUE)).isTrue();
        assertThat(TransitionWorkAccounting.reserve(state, maximum, Long.MAX_VALUE, 1)).isFalse();
        assertThat(TransitionWorkAccounting.reserve(state, maximum, Long.MAX_VALUE, 0)).isTrue();
        assertThat(state.get(KEY).orElseThrow()).isEqualTo(encoded(Long.MAX_VALUE, Integer.MAX_VALUE));
        assertThat(new OrderedLogKernel().workBudgets()).isEmpty();
        assertThat(new OrderedLogKernel().workReferences()).isEmpty();
        assertThat(new OrderedLogKernel().workRequest(new byte[0], null)).isEmpty();
    }

    private static byte[] encoded(long height, int units) {
        return ByteBuffer.allocate(12).putLong(height).putInt(units).array();
    }

    private static final class MemoryState implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();
        private int writes;

        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
            writes++;
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
    }
}
