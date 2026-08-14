package com.bloxbean.cardano.yano.api.appchain.transition;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
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

class FinalizedMessageIndexedStateMachineTest {
    @Test
    void resolvesOnlyCanonicalBoundedLauncherConfiguration() {
        assertThat(FinalizedMessageIndexedStateMachine.configuration(Map.of(), 10)).isEmpty();
        assertThat(FinalizedMessageIndexedStateMachine.configuration(Map.of(
                FinalizedMessageIndexedStateMachine.ENABLED_SETTING, "true",
                FinalizedMessageIndexedStateMachine.POLICY_SETTING, "APPLICATION_ONLY",
                FinalizedMessageIndexedStateMachine.MAX_MESSAGES_SETTING, "7"), 10))
                .hasValue(FinalizedMessageIndex.Config.applicationOnly(7));
        assertThatThrownBy(() -> FinalizedMessageIndexedStateMachine.configuration(Map.of(
                FinalizedMessageIndexedStateMachine.ENABLED_SETTING, "true",
                FinalizedMessageIndexedStateMachine.MAX_MESSAGES_SETTING, "11"), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decoratesAStandaloneApplicationInTheSameStateTransaction() {
        AppStateMachine application = new AppStateMachine() {
            @Override public String id() { return "documents"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) {
                writer.put(new byte[]{1}, new byte[]{2});
            }
        };
        var config = FinalizedMessageIndex.Config.applicationOnly(10);
        var indexed = new FinalizedMessageIndexedStateMachine(application, config);
        MapWriter writer = new MapWriter(0);
        AppMessage app = message(1, "documents.command.v1");
        AppMessage system = message(2, "~system");

        indexed.apply(context(1, List.of(app, system)), writer,
                AppEffectEmitter.rejecting("no effects"));

        assertThat(writer.get(new byte[]{1})).contains(new byte[]{2});
        assertThat(writer.get(FinalizedMessageIndex.CONFIG_KEY))
                .contains(config.canonicalBytes());
        assertThat(writer.get(FinalizedMessageIndex.messageKey(app.getMessageId()))).isPresent();
        assertThat(writer.get(FinalizedMessageIndex.messageKey(system.getMessageId()))).isEmpty();
        assertThat(indexed.capabilityManifest().crossCutting())
                .extracting(value -> value.capabilityId())
                .containsExactly("state-index:finalized-message-v1");
        assertThat(indexed.capabilityManifest().proofSubjects())
                .extracting(value -> value.subjectId())
                .containsExactly("finalized-message-v1");
        assertThat(indexed.capabilityManifest().proofSubjects().getFirst().keyNamespace())
                .isEqualTo(FinalizedMessageIndex.LOGICAL_NAMESPACE);
    }

    @Test
    void restartFailsClosedWhenCommittedConfigurationDiffers() {
        MapWriter retained = new MapWriter(4);
        retained.put(FinalizedMessageIndex.CONFIG_KEY,
                FinalizedMessageIndex.Config.applicationOnly(4).canonicalBytes());
        var indexed = new FinalizedMessageIndexedStateMachine(noop(),
                FinalizedMessageIndex.Config.applicationOnly(5));

        assertThatThrownBy(() -> indexed.init(retained,
                new AppChainInfo("chain", "00".repeat(32), 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
    }

    private static AppStateMachine noop() {
        return new AppStateMachine() {
            @Override public String id() { return "noop"; }
            @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                                        AppEffectEmitter effects) { }
        };
    }

    private static AppBlockExecutionContext context(long height, List<AppMessage> messages) {
        return AppBlockExecutionContext.fromValidatedBlock(new AppBlock(
                AppBlock.BLOCK_VERSION, "chain", height, new byte[32], 0,
                new byte[0], 10, new byte[32], new byte[32], messages,
                new byte[32], FinalityCert.empty()));
    }

    private static AppMessage message(int marker, String topic) {
        byte[] id = new byte[32]; id[0] = (byte) marker;
        return AppMessage.builder().version(1).messageId(id).chainId("chain")
                .topic(topic).sender(new byte[32]).body(new byte[]{1})
                .authProof(new byte[0]).build();
    }

    private static final class MapWriter implements AppStateWriter {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();
        private final long height;
        private MapWriter(long height) { this.height = height; }
        @Override public void put(byte[] key, byte[] value) { values.put(new Key(key), value.clone()); }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public long committedHeight() { return height; }
    }

    private record Key(byte[] value) {
        private Key { value = value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key that && Arrays.equals(value, that.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
