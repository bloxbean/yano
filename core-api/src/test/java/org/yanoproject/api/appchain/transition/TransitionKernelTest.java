package org.yanoproject.api.appchain.transition;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransitionKernelTest {
    @Test
    void eventsHaveCanonicalScalarPayloadsAndDefensiveCopies() {
        byte[] payload = TransitionScalars.encode(Map.of("x", 1L));
        assertThat(HexFormat.of().formatHex(payload)).isEqualTo("a1617801");
        TransitionEvent event = new TransitionEvent("test.accepted.v1", payload);
        payload[0] = 0;
        event.payload()[0] = 0;
        assertThat(TransitionScalars.decode(event.payload())).containsEntry("x", 1L);
        assertThatThrownBy(() -> new TransitionEvent("test", HexFormat.of().parseHex("a161781801")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionEvent("test", HexFormat.of().parseHex("a2617801617802")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionEvent("test", new byte[65_537]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransitionPlan(List.of(), List.of(), List.of(), List.of(),
                Collections.nCopies(257, event))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TransitionPlan(List.of(), List.of(), List.of(), List.of(),
                Collections.nCopies(129, event)).events()).hasSize(129);
    }

    @Test
    void scalarTypesAndOrderingHaveStableVectors() {
        Map<String, Object> fields = Map.of("b", new byte[]{1}, "a", -1L, "long", true);
        byte[] encoded = TransitionScalars.encode(fields);
        assertThat(HexFormat.of().formatHex(encoded)).isEqualTo("a361612061624101646c6f6e67f5");
        assertThat(TransitionScalars.encode(TransitionScalars.decode(encoded))).isEqualTo(encoded);
        assertThatThrownBy(() -> TransitionScalars.encode(Map.of("a", 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationDefaultsNormalizeAndUnknownSettingsFailClosed() {
        var config = new ConfigurationDescriptor(List.of(new ConfigurationDescriptor.Setting(
                "value-format", TransitionScalars.Type.TEXT, "raw")));
        assertThat(config.normalize(Map.of())).isEqualTo(config.normalize(Map.of("value-format", "raw")));
        assertThatThrownBy(() -> config.normalize(Map.of("unknown", "raw")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.normalize(Map.of("value-format", true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scalarEncodingPreflightsAggregateBytesAndValidatesUnicodeKeys() {
        assertThat(TransitionScalars.encode(Map.of("x", new byte[65530]))).hasSize(65536);
        assertThatThrownBy(() -> TransitionScalars.encode(Map.of("x", new byte[65531])))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte limit");
        assertThatThrownBy(() -> TransitionScalars.encode(Map.of("x", new byte[40000], "y", new byte[40000])))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("byte limit");
        String invalidUnicode = String.valueOf((char) 0xd800);
        assertThatThrownBy(() -> TransitionScalars.encode(Map.of(invalidUnicode, true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid Unicode text");
        assertThatThrownBy(() -> TransitionScalars.encode(Map.of("x", invalidUnicode)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid Unicode text");
        String emoji = new String(Character.toChars(0x1f642));
        assertThat(TransitionScalars.decode(TransitionScalars.encode(Map.of(emoji, emoji))))
                .containsEntry(emoji, emoji);
        assertThatThrownBy(() -> TransitionScalars.decode(HexFormat.of().parseHex("a161781b8000000000000000")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64-bit");
    }

    @Test
    void orderedLogKernelPreservesMessageAndTipWrites() {
        var kernel = new OrderedLogKernel();
        var context = new TransitionContext(5, 10, 3, new byte[32], "orders", new byte[32]);
        var decision = (TransitionDecision.Approved) kernel.decide(new byte[]{42}, context, true);
        assertThat(decision.plan().mutations()).hasSize(2);
        assertThat(decision.plan().mutations().getFirst().key())
                .isEqualTo(FinalizedMessageIndex.planMessage(context).mutations().getFirst().key());
        assertThat(decision.plan().mutations().getFirst().value())
                .isEqualTo(FinalizedMessageIndex.planMessage(context).mutations().getFirst().value());
        assertThat(decision.plan().mutations().getLast().value())
                .isEqualTo(FinalizedMessageIndex.planTip(5).mutations().getFirst().value());
        assertThat(TransitionScalars.decode(decision.plan().events().getFirst().payload()))
                .containsEntry("index", 3L).containsEntry("height", 5L);
    }

    @Test
    void defaultLogicalLookupIsDefensiveAndBounded() {
        var kernel = new OrderedLogKernel();
        byte[] key = {1};
        byte[] resolved = kernel.lookupKey(key);
        resolved[0] = 2;
        assertThat(key).containsExactly(1);
        assertThatThrownBy(() -> kernel.lookupKey(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kernel.lookupKey(new byte[65_537])).isInstanceOf(IllegalArgumentException.class);
    }
}
