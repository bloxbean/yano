package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionPlanTest {

    @Test
    void rejectedDecisionCannotMutateCandidate() {
        MapWriter writer = new MapWriter();
        boolean committed = TransitionPlans.commitIfApproved(
                TransitionDecision.reject("DENIED", "test"), writer,
                AppEffectEmitter.rejecting("effects forbidden"));

        assertThat(committed).isFalse();
        assertThat(writer.values).isEmpty();
    }

    @Test
    void plansAreDefensiveBoundedAndRejectAmbiguousKeys() {
        byte[] key = {1};
        byte[] value = {2};
        TransitionPlan plan = TransitionPlan.mutations(List.of(StateMutation.put(key, value)));
        key[0] = 9;
        value[0] = 9;

        MapWriter writer = new MapWriter();
        TransitionPlans.commit(plan, writer, AppEffectEmitter.rejecting("effects forbidden"));
        assertThat(writer.get(new byte[]{1})).hasValueSatisfying(actual ->
                assertThat(actual).containsExactly(2));

        assertThatThrownBy(() -> new TransitionPlan(
                List.of(StateMutation.put(new byte[]{1}, new byte[]{1})), List.of(),
                List.of(), List.of(StateMutation.delete(new byte[]{1}))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate state key");
    }

    @Test
    void finalizedMessageIndexPreservesGlobalPositionAndUsesVersionedRecord() {
        AppMessage hidden = message(1, "other");
        AppMessage selected = message(2, "orders");
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 8,
                new byte[32], 0, new byte[0], 99, new byte[32], new byte[32],
                List.of(hidden, selected), new byte[32], FinalityCert.empty());
        AppBlockExecutionContext routed = AppBlockExecutionContext.fromValidatedBlock(block)
                .routeToMessageIndexes(List.of(1));

        MapWriter writer = new MapWriter();
        TransitionPlans.commit(FinalizedMessageIndex.plan(
                        routed, FinalizedMessageIndex.Config.allMessages()),
                writer, AppEffectEmitter.rejecting("effects forbidden"));

        FinalizedMessageIndex.MessageRecord record = FinalizedMessageIndex.decode(
                writer.get(FinalizedMessageIndex.messageKey(selected.getMessageId())).orElseThrow());
        assertThat(record.height()).isEqualTo(8);
        assertThat(record.originalMessageIndex()).isEqualTo(1);
        assertThat(record.topic()).isEqualTo("orders");
        assertThat(writer.get(FinalizedMessageIndex.messageKey(hidden.getMessageId()))).isEmpty();
        assertThat(writer.get(FinalizedMessageIndex.TIP_KEY)).isPresent();
    }

    private static AppMessage message(int marker, String topic) {
        byte[] id = new byte[32];
        id[0] = (byte) marker;
        return AppMessage.builder().version(1).messageId(id).chainId("chain")
                .topic(topic).sender(new byte[32]).body(new byte[]{1})
                .authProof(new byte[0]).build();
    }

    private static final class MapWriter implements AppStateWriter {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();

        @Override public void put(byte[] key, byte[] value) {
            values.put(new Key(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value != null ? Optional.of(value.clone()) : Optional.empty();
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
    }

    private record Key(byte[] bytes) {
        private Key { bytes = bytes.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key that && Arrays.equals(bytes, that.bytes);
        }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
}
