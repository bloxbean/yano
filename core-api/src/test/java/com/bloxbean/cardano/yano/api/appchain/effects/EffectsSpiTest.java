package com.bloxbean.cardano.yano.api.appchain.effects;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FX-M1 SPI unit coverage (ADR app-layer/010 F1/F2/F4; ADR 010.1). */
class EffectsSpiTest {

    @Test
    void effectId_isDeterministicAndParsable() {
        EffectId id = new EffectId("payments", 1042, 0);
        assertThat(id.canonical()).isEqualTo("payments/1042/0");
        assertThat(EffectId.parse("payments/1042/0")).isEqualTo(id);
        // hash is stable and position-sensitive
        assertThat(id.hash()).isEqualTo(new EffectId("payments", 1042, 0).hash());
        assertThat(id.hash()).isNotEqualTo(new EffectId("payments", 1042, 1).hash());
        assertThat(id.hash()).isNotEqualTo(new EffectId("payments", 1043, 0).hash());
        assertThat(id.hash()).isNotEqualTo(new EffectId("other", 1042, 0).hash());
        assertThat(id.hash()).hasSize(32);
        // chain ids containing '/' still parse (height/ordinal split from the right)
        assertThat(EffectId.parse("a/b/7/3")).isEqualTo(new EffectId("a/b", 7, 3));
    }

    @Test
    void effectRecord_roundTripsCanonically() {
        EffectRecord record = new EffectRecord(EffectRecord.RECORD_VERSION, "payments", 1042, 2,
                "cardano.payment", "pay".getBytes(StandardCharsets.UTF_8), "approvals/rel-42",
                FinalityGate.L1_ANCHORED, ResultPolicy.CHAIN, 2042, new byte[]{1, 2, 3});
        EffectRecord decoded = EffectRecord.decode(record.encode());
        assertThat(decoded).usingRecursiveComparison().isEqualTo(record);
        // canonical bytes are stable → hash is stable
        assertThat(decoded.effectHash()).isEqualTo(record.effectHash());
        // CHAIN_DEFAULT never appears in a record
        assertThatThrownBy(() -> new EffectRecord(1, "c", 1, 0, "t", null, null,
                FinalityGate.CHAIN_DEFAULT, ResultPolicy.NONE, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectIntent_validatesShape() {
        assertThatThrownBy(() -> EffectIntent.of("~fx.sneaky", new byte[0]).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EffectIntent.of("webhook.post", new byte[0])
                .expiryBlocks(10).build()) // expiry without CHAIN result
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ResultPolicy.CHAIN");
        EffectIntent intent = EffectIntent.of("webhook.post", null).build();
        assertThat(intent.payload()).isEmpty();
        assertThat(intent.gate()).isEqualTo(FinalityGate.CHAIN_DEFAULT);
        assertThat(intent.result()).isEqualTo(ResultPolicy.NONE);
    }

    @Test
    void fxKeys_reservedPrefixAndRoots() {
        assertThat(FxKeys.isReserved("~fx/root/x".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(FxKeys.isReserved("~fx".getBytes(StandardCharsets.UTF_8))).isFalse(); // needs the slash
        assertThat(FxKeys.isReserved("i/item".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(FxKeys.isReserved(null)).isFalse();

        // Merkle root: empty → zeros; single leaf → the leaf's parent-of-one convention
        assertThat(FxKeys.effectsRoot(List.of())).isEqualTo(new byte[32]);
        byte[] a = new byte[32];
        a[0] = 1;
        byte[] single = FxKeys.effectsRoot(List.of(a));
        assertThat(single).isEqualTo(a); // one leaf = the root, same as messagesRoot
        // order sensitivity
        byte[] b = new byte[32];
        b[0] = 2;
        assertThat(FxKeys.effectsRoot(List.of(a, b)))
                .isNotEqualTo(FxKeys.effectsRoot(List.of(b, a)));
    }

    @Test
    void activationSchedule_missingMeansInactive() {
        ActivationSchedule schedule = ActivationSchedule.from(Map.of(
                "machines.approvals.activations.payment-effects", "100",
                "machines.approvals.activations.payment-v2", "200",
                "machines.other.activations.something", "50"), "approvals");

        assertThat(schedule.isActive("payment-effects", 99)).isFalse();
        assertThat(schedule.isActive("payment-effects", 100)).isTrue();
        assertThat(schedule.isActive("payment-v2", 150)).isFalse();
        assertThat(schedule.isActive("payment-v2", 200)).isTrue();
        // unknown change name: NEVER active (010.1-D2 safety default)
        assertThat(schedule.isActive("unknown-change", Long.MAX_VALUE)).isFalse();
        // other machines' activations are not visible
        assertThat(schedule.entries()).containsOnlyKeys("payment-effects", "payment-v2");

        assertThat(ActivationSchedule.empty().isActive("x", 1)).isFalse();
        assertThatThrownBy(() -> ActivationSchedule.from(
                Map.of("machines.m.activations.bad", "0"), "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectResult_envelopeCommitsOutcome() {
        EffectResult result = new EffectResult(new EffectId("payments", 1042, 0),
                "cardano.payment", "approvals/rel-42", EffectOutcome.CONFIRMED,
                "3f9c".getBytes(StandardCharsets.UTF_8), null, 1057);
        byte[] envelope = result.encodeEnvelope();
        assertThat(envelope).isNotEmpty();
        assertThat(result.envelopeHash()).hasSize(32);
        // outcome-sensitive
        EffectResult failed = new EffectResult(result.effectId(), result.type(), result.scope(),
                EffectOutcome.FAILED, result.externalRef(), null, 1057);
        assertThat(failed.envelopeHash()).isNotEqualTo(result.envelopeHash());
    }
}
