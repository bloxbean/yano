package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.config.AppChainApprovalsConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalsGenericEffectTest {

    @Test
    void rejectsProposalPayloadThatCannotFitTheEffectFramework() {
        Map<String, String> settings = new HashMap<>(genericSettings(
                1, 10, FinalityGate.APP_FINAL));
        settings.put("effects.max-payload-bytes", "1");
        ApprovalsStateMachine machine = new ApprovalsStateMachine(
                AppChainApprovalsConfig.fromSettings(settings),
                ActivationSchedule.from(settings, ApprovalsStateMachine.ID));
        AppMessage oversized = message(ApprovalsStateMachine.propose(
                "too-large", new byte[]{1, 2}, 1, 0), "alice");

        MemoryState state = new MemoryState();
        assertThat(machine.validate(oversized).isAccepted()).isTrue();
        assertThat(machine.validateForBlock(oversized, 0, state).isAccepted()).isTrue();
        assertThat(machine.validateForBlock(oversized, 1, state).isAccepted()).isFalse();
    }

    @Test
    void emptyPayloadEmitsOnceWithExactGenericContract() {
        Fixture fixture = fixture(1, 25, FinalityGate.L1_ANCHORED);
        fixture.machine.apply(block(1, 1,
                message(ApprovalsStateMachine.propose("empty", new byte[0], 1, 0), "alice")),
                fixture.state, fixture.emitter);
        assertThat(fixture.state.get(
                ApprovalsStateMachine.stagedEffectPayloadKey("empty"))).isPresent();

        AppMessage approval = message(ApprovalsStateMachine.approve("empty"), "bob");
        fixture.machine.apply(block(2, 2, approval), fixture.state, fixture.emitter);
        fixture.machine.apply(block(3, 3, approval), fixture.state, fixture.emitter);

        assertThat(fixture.emitter.intents).singleElement().satisfies(intent -> {
            assertThat(intent.type()).isEqualTo("demo.webhook");
            assertThat(intent.payload()).isEmpty();
            assertThat(intent.scope()).isEqualTo("approvals/on-approved/empty");
            assertThat(intent.gate()).isEqualTo(FinalityGate.L1_ANCHORED);
            assertThat(intent.result()).isEqualTo(ResultPolicy.CHAIN);
            assertThat(intent.expiryBlocks()).isEqualTo(25);
            assertThat(intent.sourceMessageId()).isEqualTo(approval.getMessageId());
        });
        assertThat(fixture.state.get(
                ApprovalsStateMachine.stagedEffectPayloadKey("empty"))).isEmpty();
        var decision = ApprovalsStateMachine.decodeItem(fixture.state.get(
                ApprovalsStateMachine.itemKey("empty")).orElseThrow());
        assertThat(decision.status()).isEqualTo(ApprovalsStateMachine.STATUS_APPROVED);
        var effect = ApprovalsStateMachine.decodeEffectState(fixture.state.get(
                ApprovalsStateMachine.effectStateKey("empty")).orElseThrow());
        assertThat(effect.status()).isEqualTo(ApprovalsStateMachine.EFFECT_STATUS_PENDING);
        assertThat(effect.effectId()).isEqualTo("chain/1/0");
    }

    @Test
    void allTerminalOutcomesPreserveApprovedDecisionAndExactEvidence() {
        for (EffectOutcome outcome : EffectOutcome.values()) {
            Fixture fixture = fixture(1, 10, FinalityGate.APP_FINAL);
            fixture.machine.apply(block(1, 1,
                    message(ApprovalsStateMachine.propose(
                            "item", "payload".getBytes(StandardCharsets.UTF_8), 1, 0), "alice"),
                    message(ApprovalsStateMachine.approve("item"), "bob")),
                    fixture.state, fixture.emitter);
            byte[] detailHash = new byte[32];
            detailHash[31] = (byte) outcome.code();
            EffectResult result = new EffectResult(new EffectId("chain", 1, 0),
                    "demo.webhook", "approvals/on-approved/item", outcome,
                    ("ref-" + outcome).getBytes(StandardCharsets.UTF_8), detailHash, 2);

            fixture.machine.onEffectResult(block(2, 2), result, fixture.state);

            var item = ApprovalsStateMachine.decodeItem(fixture.state.get(
                    ApprovalsStateMachine.itemKey("item")).orElseThrow());
            assertThat(item.status()).isEqualTo(ApprovalsStateMachine.STATUS_APPROVED);
            var effect = ApprovalsStateMachine.decodeEffectState(fixture.state.get(
                    ApprovalsStateMachine.effectStateKey("item")).orElseThrow());
            assertThat(effect.status()).isEqualTo(outcome == EffectOutcome.CONFIRMED
                    ? ApprovalsStateMachine.EFFECT_STATUS_CONFIRMED
                    : ApprovalsStateMachine.EFFECT_STATUS_FAILED);
            assertThat(effect.outcome()).isEqualTo(outcome);
            assertThat(effect.externalRef()).isEqualTo(result.externalRef());
            assertThat(effect.detailHash()).isEqualTo(detailHash);
        }
    }

    @Test
    void wrongCorrelationIsNoOpAndFirstTerminalResultWins() {
        Fixture fixture = fixture(1, 10, FinalityGate.APP_FINAL);
        fixture.machine.apply(block(1, 1,
                message(ApprovalsStateMachine.propose("item", new byte[]{1}, 1, 0), "alice"),
                message(ApprovalsStateMachine.approve("item"), "bob")),
                fixture.state, fixture.emitter);

        for (EffectResult wrong : List.of(
                result(new EffectId("chain", 9, 0), "demo.webhook",
                        "approvals/on-approved/item", EffectOutcome.CONFIRMED),
                result(new EffectId("chain", 1, 0), "other.type",
                        "approvals/on-approved/item", EffectOutcome.CONFIRMED),
                result(new EffectId("chain", 1, 0), "demo.webhook",
                        "approvals/on-approved/other", EffectOutcome.CONFIRMED),
                result(new EffectId("chain", 1, 0), "demo.webhook",
                        "wrong/item", EffectOutcome.CONFIRMED))) {
            fixture.machine.onEffectResult(block(2, 2), wrong, fixture.state);
        }
        assertThat(effectState(fixture).status())
                .isEqualTo(ApprovalsStateMachine.EFFECT_STATUS_PENDING);

        fixture.machine.onEffectResult(block(2, 2), result(new EffectId("chain", 1, 0),
                "demo.webhook", "approvals/on-approved/item", EffectOutcome.FAILED), fixture.state);
        fixture.machine.onEffectResult(block(3, 3), result(new EffectId("chain", 1, 0),
                "demo.webhook", "approvals/on-approved/item", EffectOutcome.CONFIRMED), fixture.state);
        assertThat(effectState(fixture).outcome()).isEqualTo(EffectOutcome.FAILED);
    }

    @Test
    void rejectionAndDeadlineExpiryDeleteStagedPayloadWithoutEmission() {
        Fixture rejected = fixture(1, 10, FinalityGate.APP_FINAL);
        rejected.machine.apply(block(1, 1,
                message(ApprovalsStateMachine.propose("reject", new byte[]{1}, 2, 0), "alice")),
                rejected.state, rejected.emitter);
        rejected.machine.apply(block(2, 2,
                message(ApprovalsStateMachine.reject("reject"), "bob")),
                rejected.state, rejected.emitter);
        assertThat(rejected.state.get(
                ApprovalsStateMachine.stagedEffectPayloadKey("reject"))).isEmpty();
        assertThat(rejected.emitter.intents).isEmpty();

        Fixture expired = fixture(1, 10, FinalityGate.APP_FINAL);
        expired.machine.apply(block(1, 10,
                message(ApprovalsStateMachine.propose("expire", new byte[]{1}, 1, 10), "alice")),
                expired.state, expired.emitter);
        expired.machine.apply(block(2, 11,
                message(ApprovalsStateMachine.approve("expire"), "bob")),
                expired.state, expired.emitter);
        assertThat(expired.state.get(
                ApprovalsStateMachine.stagedEffectPayloadKey("expire"))).isEmpty();
        assertThat(expired.emitter.intents).isEmpty();
        assertThat(ApprovalsStateMachine.decodeItem(expired.state.get(
                ApprovalsStateMachine.itemKey("expire")).orElseThrow()).status())
                .isEqualTo(ApprovalsStateMachine.STATUS_EXPIRED);
    }

    private static Fixture fixture(long activation, long expiry, FinalityGate gate) {
        Map<String, String> settings = genericSettings(activation, expiry, gate);
        return new Fixture(new ApprovalsStateMachine(
                AppChainApprovalsConfig.fromSettings(settings),
                ActivationSchedule.from(settings, ApprovalsStateMachine.ID)),
                new MemoryState(), new CapturingEmitter());
    }

    private static Map<String, String> genericSettings(long activation, long expiry,
                                                        FinalityGate gate) {
        return Map.of(
                "effects.enabled", "true",
                AppChainApprovalsConfig.ENABLED, "true",
                AppChainApprovalsConfig.TYPE, "demo.webhook",
                AppChainApprovalsConfig.GATE, gateName(gate),
                AppChainApprovalsConfig.EXPIRY_BLOCKS, Long.toString(expiry),
                AppChainApprovalsConfig.ACTIVATION, Long.toString(activation));
    }

    private static String gateName(FinalityGate gate) {
        return switch (gate) {
            case APP_FINAL -> "app-final";
            case L1_ANCHORED -> "l1-anchored";
            case ZK_SETTLED -> "zk-settled";
            case CHAIN_DEFAULT -> "chain-default";
        };
    }

    private static EffectResult result(EffectId id, String type, String scope,
                                       EffectOutcome outcome) {
        return new EffectResult(id, type, scope, outcome, new byte[0], null, 2);
    }

    private static ApprovalsStateMachine.ApprovalEffectState effectState(Fixture fixture) {
        return ApprovalsStateMachine.decodeEffectState(fixture.state.get(
                ApprovalsStateMachine.effectStateKey("item")).orElseThrow());
    }

    private static AppMessage message(byte[] body, String senderText) {
        byte[] sender = java.util.Arrays.copyOf(
                senderText.getBytes(StandardCharsets.UTF_8), 32);
        byte[] id = AppMessage.computeMessageId(
                "chain", "approvals", sender, 1, Long.MAX_VALUE, body);
        return AppMessage.builder().messageId(id).chainId("chain").topic("approvals")
                .sender(sender).senderSeq(1).expiresAt(Long.MAX_VALUE).body(body)
                .authScheme(0).authProof(new byte[]{1}).build();
    }

    private static AppBlock block(long height, long timestamp, AppMessage... messages) {
        return new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], timestamp,
                new byte[32], new byte[32], List.of(messages), new byte[32],
                FinalityCert.empty());
    }

    private record Fixture(ApprovalsStateMachine machine,
                           MemoryState state,
                           CapturingEmitter emitter) {
    }

    private static final class CapturingEmitter implements AppEffectEmitter {
        private final List<EffectIntent> intents = new ArrayList<>();

        @Override
        public EffectId emit(EffectIntent intent) {
            intents.add(intent);
            return new EffectId("chain", 1, intents.size() - 1);
        }

        @Override
        public long pendingCount() {
            return 0;
        }
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
