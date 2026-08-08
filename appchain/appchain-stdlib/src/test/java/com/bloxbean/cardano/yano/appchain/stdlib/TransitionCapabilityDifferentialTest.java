package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionContext;
import com.bloxbean.cardano.yano.api.appchain.transition.TransitionPlans;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionCapabilityDifferentialTest {
    private static final AppEffectEmitter NO_EFFECTS =
            AppEffectEmitter.rejecting("capability does not emit effects");

    @Test
    void documentMachineAndCapabilityProduceIdenticalState() {
        AppMessage message = message(1, "doc-trail.command.v1", sender(3),
                DocTrailStateMachine.append("passport-1", sender(7), "ipfs://doc"));
        AppBlockExecutionContext execution = execution(List.of(message));
        MapWriter standalone = new MapWriter();
        new DocTrailStateMachine().apply(execution, standalone, NO_EFFECTS);

        MapWriter composed = new MapWriter();
        DocTrailTransitions capability = new DocTrailTransitions();
        TransitionPlans.commitIfApproved(capability.decide(message.getBody(),
                        TransitionContext.of(execution.block(), 0, message), composed),
                composed, NO_EFFECTS);

        assertStateEqual(composed, standalone);
    }

    @Test
    void kvMachineAndCapabilityMatchAcrossPendingSameKeyWritesAndRejection() {
        byte[] key = {9};
        AppMessage create = message(1, "kv", sender(1), KvRegistryStateMachine.put(key, new byte[]{4}));
        AppMessage update = message(2, "kv", sender(1), KvRegistryStateMachine.put(key, new byte[]{5}));
        AppMessage rejected = message(3, "kv", sender(2), KvRegistryStateMachine.delete(key));
        List<AppMessage> messages = List.of(create, update, rejected);
        AppBlockExecutionContext execution = execution(messages);

        MapWriter standalone = new MapWriter();
        new KvRegistryStateMachine().apply(execution, standalone, NO_EFFECTS);

        MapWriter composed = new MapWriter();
        KvRegistryTransitions capability = new KvRegistryTransitions(
                KvRegistryTransitions.ValueFormat.RAW);
        for (int index = 0; index < messages.size(); index++) {
            AppMessage message = messages.get(index);
            KvRegistryTransitions.Command command = capability.decodeCommand(message.getBody());
            TransitionPlans.commitIfApproved(capability.decide(command,
                            TransitionContext.of(execution.block(), index, message),
                            new KvRegistryTransitions.Facts(composed.get(command.key()))),
                    composed, NO_EFFECTS);
        }

        assertStateEqual(composed, standalone);
        assertThat(KvRegistryStateMachine.decodeValue(composed.get(key).orElseThrow()))
                .containsExactly(5);
    }

    private static AppBlockExecutionContext execution(List<AppMessage> messages) {
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "chain", 4,
                new byte[32], 0, new byte[0], 10, new byte[32], new byte[32],
                messages, new byte[32], FinalityCert.empty());
        return AppBlockExecutionContext.fromValidatedBlock(block);
    }

    private static AppMessage message(int marker, String topic, byte[] sender, byte[] body) {
        byte[] id = new byte[32];
        id[0] = (byte) marker;
        return AppMessage.builder().version(1).messageId(id).chainId("chain")
                .topic(topic).sender(sender).body(body).authProof(new byte[0]).build();
    }

    private static byte[] sender(int marker) {
        byte[] sender = new byte[32];
        sender[0] = (byte) marker;
        return sender;
    }

    private static void assertStateEqual(MapWriter actual, MapWriter expected) {
        assertThat(actual.values.keySet()).isEqualTo(expected.values.keySet());
        expected.values.forEach((key, value) ->
                assertThat(actual.values.get(key)).containsExactly(value));
    }

    private static final class MapWriter implements AppStateWriter {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();
        @Override public void put(byte[] key, byte[] value) {
            values.put(new Key(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
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
