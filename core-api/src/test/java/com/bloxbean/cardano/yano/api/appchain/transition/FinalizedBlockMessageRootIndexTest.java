package com.bloxbean.cardano.yano.api.appchain.transition;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalizedBlockMessageRootIndexTest {
    @Test
    void defaultSelectionIsEnabledAndExplicitDisablementChangesIdentity() {
        var enabled = FinalizedBlockMessageRootIndexedStateMachine.configuration(Map.of(), 20);
        var disabled = FinalizedBlockMessageRootIndexedStateMachine.configuration(Map.of(
                FinalizedBlockMessageRootIndexedStateMachine.ENABLED_SETTING, "false"), 20);
        assertThat(enabled.enabled()).isTrue();
        assertThat(disabled.enabled()).isFalse();
        assertThat(enabled.digest()).isNotEqualTo(disabled.digest());
        assertThatThrownBy(() -> FinalizedBlockMessageRootIndexedStateMachine.configuration(Map.of(
                FinalizedBlockMessageRootIndexedStateMachine.MAX_MESSAGES_SETTING, "21"), 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesOneStrictCanonicalRecordPerBlockAndConfigOnlyOnce() {
        var config = FinalizedBlockMessageRootIndex.Config.primary(true, 10);
        var indexed = new FinalizedBlockMessageRootIndexedStateMachine(noop(), config);
        MapWriter writer = new MapWriter(0);
        AppBlock first = block(1, List.of(message(1), message(2), message(3)));
        indexed.apply(AppBlockExecutionContext.fromValidatedBlock(first), writer,
                AppEffectEmitter.rejecting("none"));

        assertThat(writer.values).hasSize(2);
        assertThat(writer.get(FinalizedBlockMessageRootIndex.CONFIG_KEY))
                .contains(config.canonicalBytes());
        var decoded = FinalizedBlockMessageRootIndex.decode(writer.get(
                FinalizedBlockMessageRootIndex.blockKey(1)).orElseThrow());
        assertThat(decoded.height()).isEqualTo(1);
        assertThat(decoded.messageCount()).isEqualTo(3);
        assertThat(decoded.messagesRoot()).isEqualTo(first.messagesRoot());

        writer.height = 1;
        AppBlock empty = block(2, List.of());
        indexed.apply(AppBlockExecutionContext.fromValidatedBlock(empty), writer,
                AppEffectEmitter.rejecting("none"));
        assertThat(writer.values).hasSize(3);
        var emptyRecord = FinalizedBlockMessageRootIndex.decode(writer.get(
                FinalizedBlockMessageRootIndex.blockKey(2)).orElseThrow());
        assertThat(emptyRecord.messageCount()).isZero();
        assertThat(emptyRecord.messagesRoot()).isEqualTo(AppBlockCodec.messagesRoot(List.of()));
    }

    @Test
    void disabledProfileAddsNoStateAndPublishesNoSubject() {
        var indexed = new FinalizedBlockMessageRootIndexedStateMachine(noop(),
                FinalizedBlockMessageRootIndex.Config.primary(false, 10));
        MapWriter writer = new MapWriter(0);
        indexed.apply(AppBlockExecutionContext.fromValidatedBlock(block(1, List.of(message(1)))),
                writer, AppEffectEmitter.rejecting("none"));
        assertThat(writer.values).isEmpty();
        assertThat(indexed.capabilityManifest().crossCutting().getFirst().enabled()).isFalse();
        assertThat(indexed.capabilityManifest().proofSubjects()).isEmpty();
    }

    @Test
    void rejectsDuplicateHeightRestartDriftAndMalformedRecords() {
        var config = FinalizedBlockMessageRootIndex.Config.primary(true, 10);
        var indexed = new FinalizedBlockMessageRootIndexedStateMachine(noop(), config);
        MapWriter writer = new MapWriter(0);
        var context = AppBlockExecutionContext.fromValidatedBlock(block(1, List.of(message(1))));
        indexed.apply(context, writer, AppEffectEmitter.rejecting("none"));
        assertThatThrownBy(() -> indexed.apply(context, writer,
                AppEffectEmitter.rejecting("none"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        MapWriter retained = new MapWriter(5);
        retained.put(FinalizedBlockMessageRootIndex.CONFIG_KEY,
                FinalizedBlockMessageRootIndex.Config.primary(true, 9).canonicalBytes());
        assertThatThrownBy(() -> indexed.init(retained,
                new AppChainInfo("chain", "00".repeat(32), 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incompatible");

        Array wrongRoot = new Array();
        wrongRoot.add(new UnsignedInteger(1));
        wrongRoot.add(new UnsignedInteger(1));
        wrongRoot.add(new ByteString(new byte[31]));
        wrongRoot.add(new UnsignedInteger(1));
        assertThatThrownBy(() -> FinalizedBlockMessageRootIndex.decode(
                CborSerializationUtil.serialize(wrongRoot)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AppStateMachine noop() {
        return new AppStateMachine() {
            @Override public String id() { return "noop"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
        };
    }

    private static AppBlock block(long height, List<AppMessage> messages) {
        return new AppBlock(AppBlock.BLOCK_VERSION, "chain", height, new byte[32], 0,
                new byte[0], 10, AppBlockCodec.messagesRoot(messages), new byte[32], messages,
                new byte[32], FinalityCert.empty());
    }

    private static AppMessage message(int marker) {
        byte[] id = new byte[32]; id[0] = (byte) marker;
        return AppMessage.builder().version(1).messageId(id).chainId("chain").topic("records")
                .sender(new byte[32]).body(new byte[]{1}).authProof(new byte[0]).build();
    }

    private static final class MapWriter implements AppStateWriter {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();
        private long height;
        private MapWriter(long height) { this.height = height; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(new Key(key.clone()), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public long committedHeight() { return height; }
    }

    private record Key(byte[] value) {
        @Override public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(value, key.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
