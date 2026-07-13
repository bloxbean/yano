package com.bloxbean.cardano.yano.api.appchain.effects;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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

        // Empty → zeros; non-empty roots bind both the raw tree and count.
        assertThat(FxKeys.effectsRoot(List.of())).isEqualTo(new byte[32]);
        byte[] a = new byte[32];
        a[0] = 1;
        assertThat(FxKeys.effectsRoot(List.of(a))).hasSize(32).isNotEqualTo(a);
        // order sensitivity
        byte[] b = new byte[32];
        b[0] = 2;
        assertThat(FxKeys.effectsRoot(List.of(a, b)))
                .isNotEqualTo(FxKeys.effectsRoot(List.of(b, a)));
        // pass-through odd promotion: a duplicated trailing leaf CHANGES the
        // root (CVE-2012-2459 malleability class is excluded by construction)
        byte[] c = new byte[32];
        c[0] = 3;
        assertThat(FxKeys.effectsRoot(List.of(a, b, c)))
                .isNotEqualTo(FxKeys.effectsRoot(List.of(a, b, c, c)));
    }

    @Test
    void effectMerkleProof_roundTripsEveryPositionAndRejectsMalformedShape() {
        for (int count = 1; count <= 32; count++) {
            int treeSize = count;
            List<byte[]> hashes = IntStream.range(0, treeSize)
                    .mapToObj(i -> {
                        byte[] hash = new byte[32];
                        hash[0] = (byte) treeSize;
                        hash[31] = (byte) i;
                        return hash;
                    }).toList();
            byte[] root = FxKeys.effectsRoot(hashes);
            for (int index = 0; index < count; index++) {
                List<EffectProofStep> path = FxKeys.effectsProof(hashes, index);
                assertThat(FxKeys.verifyEffectsProof(
                        hashes.get(index), index, count, path, root)).isTrue();
                for (int claimedCount = 1; claimedCount <= 32; claimedCount++) {
                    if (claimedCount != count && index < claimedCount) {
                        assertThat(FxKeys.verifyEffectsProof(hashes.get(index), index,
                                claimedCount, path, root))
                                .as("actual count %s, claimed count %s, index %s",
                                        count, claimedCount, index)
                                .isFalse();
                    }
                }
            }
        }

        List<byte[]> three = IntStream.range(0, 3).mapToObj(i -> {
            byte[] hash = new byte[32];
            hash[0] = (byte) (i + 1);
            return hash;
        }).toList();
        List<EffectProofStep> lastPath = FxKeys.effectsProof(three, 2);
        assertThat(lastPath).extracting(EffectProofStep::side)
                .containsExactly(EffectProofSide.PASS_THROUGH, EffectProofSide.LEFT);

        byte[] root = FxKeys.effectsRoot(three);
        List<EffectProofStep> wrongSide = List.of(
                new EffectProofStep(EffectProofSide.RIGHT, three.get(0)),
                lastPath.get(1));
        assertThat(FxKeys.verifyEffectsProof(three.get(2), 2, 3, wrongSide, root)).isFalse();
        assertThat(FxKeys.verifyEffectsProof(three.get(2), 2, 3,
                lastPath.subList(0, 1), root)).isFalse();
        java.util.ArrayList<EffectProofStep> extraStep = new java.util.ArrayList<>(lastPath);
        extraStep.add(lastPath.get(0));
        assertThat(FxKeys.verifyEffectsProof(three.get(2), 2, 3, extraStep, root)).isFalse();
        byte[] tampered = three.get(2).clone();
        tampered[1] = 9;
        assertThat(FxKeys.verifyEffectsProof(tampered, 2, 3, lastPath, root)).isFalse();
        assertThat(FxKeys.verifyEffectsProof(three.get(0), 0, 4,
                FxKeys.effectsProof(three, 0), root)).isFalse();
        assertThat(FxKeys.verifyEffectsProof(three.get(0), 0, Integer.MAX_VALUE,
                FxKeys.effectsProof(three, 0), root)).isFalse();
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
