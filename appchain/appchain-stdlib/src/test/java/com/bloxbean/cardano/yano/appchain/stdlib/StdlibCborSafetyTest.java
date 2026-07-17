package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StdlibCborSafetyTest {

    @Test
    void deeplyNestedCommandsAreOrdinaryDeterministicRejections() {
        byte[] hostile = nestedArrays(10_000);

        assertThatThrownBy(() -> KvRegistryStateMachine.Command.decode(hostile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApprovalsStateMachine.Command.decode(hostile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BalancesStateMachine.Command.decode(hostile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DocTrailStateMachine.Command.decode(hostile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateAndApplyUseTheSameBoundedCommandDecoder() {
        byte[] hostile = nestedArrays(10_000);
        AppMessage message = message(hostile);
        AppBlock block = block(message);

        assertThat(new KvRegistryStateMachine().validate(message).isAccepted())
                .isFalse();
        assertThat(new ApprovalsStateMachine().validate(message).isAccepted())
                .isFalse();
        assertThat(new BalancesStateMachine().validate(message).isAccepted())
                .isFalse();
        assertThat(new DocTrailStateMachine().validate(message).isAccepted())
                .isFalse();

        MapWriter writer = new MapWriter();
        assertThatCode(() -> new KvRegistryStateMachine().apply(block, writer))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ApprovalsStateMachine().apply(block, writer))
                .doesNotThrowAnyException();
        assertThatCode(() -> new BalancesStateMachine().apply(block, writer))
                .doesNotThrowAnyException();
        assertThatCode(() -> new DocTrailStateMachine().apply(block, writer))
                .doesNotThrowAnyException();
        assertThat(writer.values).isEmpty();
    }

    @Test
    void nestedKvCborValueIsBoundedBeforeRecursiveDecode() {
        KvRegistryStateMachine machine = new KvRegistryStateMachine(
                KvRegistryStateMachine.ValueFormat.CBOR);
        byte[] command = KvRegistryStateMachine.put(new byte[]{1}, nestedArrays(10_000));

        assertThat(machine.validate(message(command)).isAccepted()).isFalse();
        assertThat(machine.validate(message(
                KvRegistryStateMachine.put(new byte[]{1},
                        BalancesStateMachine.mint("alice", BigInteger.ONE)))).isAccepted()).isTrue();
    }

    private static byte[] nestedArrays(int depth) {
        byte[] bytes = new byte[depth + 1];
        java.util.Arrays.fill(bytes, 0, depth, (byte) 0x81);
        bytes[depth] = 0;
        return bytes;
    }

    private static AppMessage message(byte[] body) {
        return AppMessage.builder().version(1).messageId(new byte[32]).chainId("chain")
                .topic("command").sender(new byte[32]).senderSeq(1).expiresAt(Long.MAX_VALUE)
                .body(body).authScheme(0).authProof(new byte[64]).build();
    }

    private static AppBlock block(AppMessage message) {
        return new AppBlock(1, "chain", 1, new byte[32], 0, new byte[0], 1,
                new byte[32], new byte[32], List.of(message), new byte[32],
                FinalityCert.empty());
    }

    private static final class MapWriter implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(java.util.HexFormat.of().formatHex(key)));
        }

        @Override
        public byte[] stateRoot() {
            return new byte[32];
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(java.util.HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(java.util.HexFormat.of().formatHex(key));
        }
    }
}
