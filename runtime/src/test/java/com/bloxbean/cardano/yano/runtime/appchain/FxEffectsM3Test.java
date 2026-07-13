package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FxKeys;
import com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.stdlib.ApprovalsStateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FX-M3 result loop (ADR app-layer/010 F8/F9): fail-closed ~fx/result
 * incorporation (first result wins), the consensus result window, the
 * result-beats-expiry ordering, onEffectResult delivery, injection
 * surfacing, and the stdlib approvals payments flow (ADR-010 §8.1).
 */
@Timeout(120)
class FxEffectsM3Test {

    private static final Map<String, String> FX_SETTINGS = Map.of(
            "effects.enabled", "true",
            "effects.max-per-block", "8",
            "effects.max-payload-bytes", "4096");

    // ------------------------------------------------------------------

    @Test
    void resultIncorporation_closesEffect_andNotifiesMachine(@TempDir Path dir) {
        RecordingMachine machine = new RecordingMachine(0);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            pipeline.apply(msg("t", "emit-1"));                    // h1: emits CHAIN effect 1/0
            assertThat(pipeline.store.fxOpenCount()).isEqualTo(1);

            byte[] result = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED,
                    "tx-abc".getBytes(StandardCharsets.UTF_8), null).encode();
            FxKernel.Result fx = pipeline.apply(msg(FxResultBody.TOPIC, result)); // h2

            assertThat(fx.incorporated()).hasSize(1);
            assertThat(fx.incorporated().get(0).outcome()).isEqualTo(EffectOutcome.CONFIRMED);
            assertThat(pipeline.store.fxClosed(1, 0)).isTrue();
            assertThat(pipeline.store.fxOpenCount()).isZero();
            // per-effect done leaf commits the envelope hash
            byte[] done = pipeline.store.stateGet(
                    FxKeys.doneKey(fx.incorporated().get(0).effectId())).orElseThrow();
            assertThat(done).isEqualTo(fx.incorporated().get(0).envelopeHash());
            // the machine SAW it
            assertThat(machine.results).hasSize(1);
            assertThat(machine.results.get(0).confirmed()).isTrue();
            assertThat(new String(machine.results.get(0).externalRef(), StandardCharsets.UTF_8))
                    .isEqualTo("tx-abc");
        }
    }

    @Test
    void firstResultWins_duplicatesAndLateResultsNoOp(@TempDir Path dir) {
        RecordingMachine machine = new RecordingMachine(0);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            pipeline.apply(msg("t", "emit-1"));                    // h1
            byte[] confirmed = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED,
                    "first".getBytes(StandardCharsets.UTF_8), null).encode();
            byte[] failed = new FxResultBody(1, 1, 0, EffectOutcome.FAILED,
                    "second".getBytes(StandardCharsets.UTF_8), null).encode();

            // Same block: both present, first (in block order) wins
            FxKernel.Result fx = pipeline.apply(
                    msg(FxResultBody.TOPIC, confirmed), msg(FxResultBody.TOPIC, failed));
            assertThat(fx.incorporated()).hasSize(1);
            assertThat(fx.incorporated().get(0).outcome()).isEqualTo(EffectOutcome.CONFIRMED);

            // Later block: closed → deterministic no-op
            FxKernel.Result later = pipeline.apply(msg(FxResultBody.TOPIC, failed));
            assertThat(later.incorporated()).isEmpty();
            assertThat(machine.results).hasSize(1);
        }
    }

    @Test
    void resultBeatsExpiry_inTheSameBlock(@TempDir Path dir) {
        RecordingMachine machine = new RecordingMachine(2);      // expiry at emit height + 2
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            pipeline.apply(msg("t", "emit-1"));                    // h1: expires at h3
            pipeline.apply();                                      // h2
            byte[] result = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED,
                    "just-in-time".getBytes(StandardCharsets.UTF_8), null).encode();
            FxKernel.Result fx = pipeline.apply(msg(FxResultBody.TOPIC, result)); // h3: sweep block

            // The result (processed FIRST) wins; the sweep skips the closed effect
            assertThat(fx.incorporated()).hasSize(1);
            assertThat(fx.incorporated().get(0).outcome()).isEqualTo(EffectOutcome.CONFIRMED);
        }
    }

    @Test
    void lateResult_afterExpiry_noOps(@TempDir Path dir) {
        RecordingMachine machine = new RecordingMachine(1);      // expires at h2
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            pipeline.apply(msg("t", "emit-1"));                    // h1
            FxKernel.Result sweep = pipeline.apply();              // h2: EXPIRED incorporated
            assertThat(sweep.incorporated()).hasSize(1);
            assertThat(sweep.incorporated().get(0).outcome()).isEqualTo(EffectOutcome.EXPIRED);

            byte[] result = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED,
                    "too-late".getBytes(StandardCharsets.UTF_8), null).encode();
            FxKernel.Result late = pipeline.apply(msg(FxResultBody.TOPIC, result)); // h3
            assertThat(late.incorporated()).isEmpty();
            assertThat(machine.results).hasSize(1); // only the EXPIRED delivery
        }
    }

    @Test
    void malformedUnknownNonChainAndOutOfWindow_allNoOp(@TempDir Path dir) {
        Map<String, String> settings = new java.util.LinkedHashMap<>(FX_SETTINGS);
        settings.put("effects.result-window-blocks", "2");
        RecordingMachine machine = new RecordingMachine(0, ResultPolicy.NONE);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, settings)) {
            pipeline.apply(msg("t", "emit-1"));                    // h1: NONE-policy effect

            // Malformed body, unknown effect, NONE-policy target — all no-op,
            // and the block still applies (a result can never stall the chain)
            byte[] unknown = new FxResultBody(1, 1, 7, EffectOutcome.CONFIRMED, null, null).encode();
            byte[] nonChain = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED, null, null).encode();
            FxKernel.Result fx = pipeline.apply(
                    msg(FxResultBody.TOPIC, "garbage".getBytes(StandardCharsets.UTF_8)),
                    msg(FxResultBody.TOPIC, unknown),
                    msg(FxResultBody.TOPIC, nonChain));
            assertThat(fx.incorporated()).isEmpty();

            pipeline.apply();                                      // h3
            // h4: effect from h1 is now outside window (4-1=3 > 2) → no-op
            // BEFORE any CF lookup (node-local pruning can never matter)
            FxKernel.Result windowed = pipeline.apply(msg(FxResultBody.TOPIC, nonChain));
            assertThat(windowed.incorporated()).isEmpty();
        }
    }

    @Test
    void perBlockMode_resultsRootCoversResultsAndExpiry(@TempDir Path dir) {
        Map<String, String> settings = new java.util.LinkedHashMap<>(FX_SETTINGS);
        settings.put("effects.outcome-commitment", "per-block");
        RecordingMachine machine = new RecordingMachine(2);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, settings)) {
            pipeline.apply(msg("t", "emit-1"), msg("t", "emit-2")); // h1: two effects, expire h3
            pipeline.apply();                                       // h2
            byte[] result = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED,
                    "ref".getBytes(StandardCharsets.UTF_8), null).encode();
            FxKernel.Result fx = pipeline.apply(msg(FxResultBody.TOPIC, result)); // h3

            // 1/0 closed by result, 1/1 by sweep — one resultsRoot over both,
            // results first, then expirations (processing order)
            assertThat(fx.incorporated()).hasSize(2);
            assertThat(fx.incorporated().get(0).outcome()).isEqualTo(EffectOutcome.CONFIRMED);
            assertThat(fx.incorporated().get(1).outcome()).isEqualTo(EffectOutcome.EXPIRED);
            List<byte[]> hashes = fx.incorporated().stream().map(EffectResult::envelopeHash).toList();
            byte[] leaf = pipeline.store.stateGet(FxKeys.resultsRootKey(3)).orElseThrow();
            assertThat(leaf).isEqualTo(FxKeys.effectsRoot(hashes));
            // no per-effect done leaves in per-block mode
            assertThat(pipeline.store.stateGet(
                    FxKeys.doneKey(fx.incorporated().get(0).effectId()))).isEmpty();
        }
    }

    @Test
    void injectionSurfacing_confirmedAndFailed_untilClosed(@TempDir Path dir) throws Exception {
        AtomicReference<EffectExecution> outcome = new AtomicReference<>(
                EffectExecution.confirmed("tx-1".getBytes(StandardCharsets.UTF_8)));
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "flip"; }
            @Override public boolean supports(String type) { return "test.action".equals(type); }
            @Override public EffectExecution execute(
                    com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext ctx,
                    com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect effect) {
                return outcome.get();
            }
        };
        RecordingMachine machine = new RecordingMachine(0);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     new EffectRuntime.Settings(true, Set.of(), 50, 2, 3, 1, 5, 0, 100),
                     List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.apply(msg("t", "emit-1"));                    // h1: CHAIN effect 1/0
            pipeline.apply(msg("t", "emit-2"));                    // h2: CHAIN effect 2/0
            outcome.set(EffectExecution.failed("definitive-no", false));
            runtime.tick(); // executes 1/0 (confirmed set later? both pending)
            // Let both execute: 1/0 and 2/0 — flip outcome between ticks
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                runtime.tick();
                var injections = runtime.pendingInjections(10, 0);
                if (!injections.isEmpty()) {
                    // FAILED local terminal for a CHAIN effect surfaces as injectable
                    assertThat(injections).anySatisfy(injection ->
                            assertThat(injection.confirmed()).isFalse());
                    break;
                }
                Thread.sleep(20);
            }

            // Incorporate a result → closure → no longer injectable
            byte[] result = new FxResultBody(1, 1, 0, EffectOutcome.FAILED,
                    "definitive-no".getBytes(StandardCharsets.UTF_8), null).encode();
            pipeline.apply(msg(FxResultBody.TOPIC, result));
            assertThat(runtime.pendingInjections(10, 0))
                    .noneMatch(injection -> injection.height() == 1 && injection.ordinal() == 0);
        }
    }

    @Test
    void resultReadyIndex_preventsOldStatusRowsFromStarvingInjection(@TempDir Path dir)
            throws Exception {
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "confirm"; }
            @Override public boolean supports(String type) { return "test.action".equals(type); }
            @Override public EffectExecution execute(
                    com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext ctx,
                    com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect effect) {
                return EffectExecution.confirmed("tx-indexed".getBytes(StandardCharsets.UTF_8));
            }
        };
        RecordingMachine machine = new RecordingMachine(0);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     new EffectRuntime.Settings(true, Set.of(), 50, 2, 3, 1, 5, 0, 100),
                     List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            // More than Settings.scanLimit() irrelevant rows sort before h1.
            // The pre-index implementation scanned this prefix and never saw
            // the newer DONE result.
            for (int ordinal = 0; ordinal < 4_200; ordinal++) {
                pipeline.store.fxRuntimePutStatus(0, ordinal, FxStatusRecord.pending());
            }
            pipeline.apply(msg("t", "emit-indexed"));

            long deadline = System.currentTimeMillis() + 10_000;
            while (!pipeline.store.fxResultReadyExists(1, 0)
                    && System.currentTimeMillis() < deadline) {
                runtime.tick();
                Thread.sleep(10);
            }
            assertThat(pipeline.store.fxResultReadyExists(1, 0)).isTrue();
            assertThat(runtime.pendingInjections(1, 0)).singleElement().satisfies(injection -> {
                assertThat(injection.height()).isEqualTo(1);
                assertThat(injection.ordinal()).isZero();
                assertThat(injection.confirmed()).isTrue();
            });

            byte[] result = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED,
                    "tx-indexed".getBytes(StandardCharsets.UTF_8), null).encode();
            pipeline.apply(msg(FxResultBody.TOPIC, result));
            assertThat(pipeline.store.fxResultReadyExists(1, 0)).isFalse();
            assertThat(runtime.pendingInjections(1, 0)).isEmpty();
        }
    }

    @Test
    void resultReadyIndex_backfillsPreIndexDoneChainOutcomes(@TempDir Path dir) {
        RecordingMachine machine = new RecordingMachine(0);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            pipeline.apply(msg("t", "emit-legacy"));
            pipeline.store.bindFxRuntimeOwner("legacy-owner");
            pipeline.store.fxPutIntakeCursor(1);
            pipeline.store.fxRuntimePutStatus(1, 0, FxStatusRecord.pending()
                    .done("tx-legacy".getBytes(StandardCharsets.UTF_8)));
            assertThat(pipeline.store.fxResultReadyExists(1, 0)).isFalse();

            try (EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    new EffectRuntime.Settings(true, Set.of(), 50, 2, 3, 1, 5, 0, 100),
                    List.of(), Map.of(), "legacy-owner", LoggerFactory.getLogger("fx"))) {
                assertThat(pipeline.store.fxResultReadyExists(1, 0)).isTrue();
                assertThat(runtime.pendingInjections(1, 0)).singleElement()
                        .satisfies(injection -> assertThat(injection.height()).isEqualTo(1));
            }
        }
    }

    @Test
    void resultReadyInjection_roundRobinsBeyondOneReinjectionWindow(@TempDir Path dir) {
        Map<String, String> settings = Map.of(
                "effects.enabled", "true",
                "effects.max-per-block", "512",
                "effects.max-payload-bytes", "4096");
        RecordingMachine machine = new RecordingMachine(0);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, settings)) {
            Msg[] messages = IntStream.range(0, 401)
                    .mapToObj(i -> msg("t", "emit-fair-" + i))
                    .toArray(Msg[]::new);
            pipeline.apply(messages);
            pipeline.store.bindFxRuntimeOwner("fair-owner");
            pipeline.store.fxPutIntakeCursor(1);
            for (int ordinal = 0; ordinal < messages.length; ordinal++) {
                pipeline.store.fxRuntimeComplete(1, ordinal,
                        FxStatusRecord.pending().done(
                                ("tx-" + ordinal).getBytes(StandardCharsets.UTF_8)), true);
            }

            try (EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    new EffectRuntime.Settings(true, Set.of(), 50, 2, 3, 1, 5, 0, 100),
                    List.of(), Map.of(), "fair-owner", LoggerFactory.getLogger("fx"))) {
                Set<Integer> seen = new java.util.HashSet<>();
                for (int batch = 0; batch < 13; batch++) {
                    runtime.pendingInjections(32, 60_000)
                            .forEach(injection -> seen.add(injection.ordinal()));
                }
                assertThat(seen).hasSize(messages.length)
                        .contains(IntStream.range(0, messages.length).boxed().toArray(Integer[]::new));
            }
        }
    }

    // ------------------------------------------------------------------
    // Approvals payments flow (ADR-010 §8.1)
    // ------------------------------------------------------------------

    @Test
    void approvalsPayments_emitOnApproval_paidOnResult(@TempDir Path dir) {
        Map<String, String> approvalsSettings = Map.of(
                "machines.approvals.payments", "true",
                "machines.approvals.payment-expiry-blocks", "100",
                "machines.approvals.activations.payments", "1");
        ApprovalsStateMachine machine = new ApprovalsStateMachine(
                ApprovalsStateMachine.PaymentsConfig.from(approvalsSettings),
                ActivationSchedule.from(approvalsSettings, ApprovalsStateMachine.ID));
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            byte[] payment = "{\"to\":\"addr1..\",\"lovelace\":100}".getBytes(StandardCharsets.UTF_8);
            // h1: propose (2-of-n) + first approval; h2: second approval → APPROVED + emit
            pipeline.apply(
                    msg("t", ApprovalsStateMachine.propose("rel-42", payment, 2, 0), "alice"),
                    msg("t", ApprovalsStateMachine.approve("rel-42"), "alice"));
            FxKernel.Result fx = pipeline.apply(
                    msg("t", ApprovalsStateMachine.approve("rel-42"), "bob"));

            assertThat(fx.emitted()).hasSize(1);
            var record = fx.emitted().get(0).record();
            assertThat(record.type()).isEqualTo("cardano.payment");
            assertThat(record.scope()).isEqualTo("approvals/rel-42");
            assertThat(record.payload()).isEqualTo(payment);
            // parked payment body deleted; link written
            assertThat(pipeline.store.stateGet(ApprovalsStateMachine.paymentKey("rel-42"))).isEmpty();
            assertThat(pipeline.store.stateGet(ApprovalsStateMachine.effectLinkKey("rel-42")))
                    .isPresent();

            // Payment result → PAID with the tx ref recorded
            byte[] result = new FxResultBody(1, record.height(), record.ordinal(),
                    EffectOutcome.CONFIRMED, "3f9c".getBytes(StandardCharsets.UTF_8), null).encode();
            pipeline.apply(msg(FxResultBody.TOPIC, result));
            var item = ApprovalsStateMachine.decodeItem(
                    pipeline.store.stateGet(ApprovalsStateMachine.itemKey("rel-42")).orElseThrow());
            assertThat(item.status()).isEqualTo(ApprovalsStateMachine.STATUS_PAID);
            assertThat(pipeline.store.stateGet(ApprovalsStateMachine.paymentRefKey("rel-42")))
                    .isPresent();
        }
    }

    @Test
    void approvalsPayments_missingActivation_doesNotEmitOrParkPayload(@TempDir Path dir) {
        ApprovalsStateMachine machine = new ApprovalsStateMachine(
                ApprovalsStateMachine.PaymentsConfig.from(Map.of(
                        "machines.approvals.payments", "true")),
                ActivationSchedule.empty());
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            byte[] payment = "{\"to\":\"addr1..\",\"lovelace\":100}"
                    .getBytes(StandardCharsets.UTF_8);
            pipeline.apply(msg("t", ApprovalsStateMachine.propose("rel-old", payment, 1, 0), "alice"));
            FxKernel.Result fx = pipeline.apply(
                    msg("t", ApprovalsStateMachine.approve("rel-old"), "alice"));

            assertThat(fx.emitted()).isEmpty();
            assertThat(pipeline.store.stateGet(ApprovalsStateMachine.paymentKey("rel-old"))).isEmpty();
            assertThat(pipeline.store.stateGet(ApprovalsStateMachine.effectLinkKey("rel-old"))).isEmpty();
            var item = ApprovalsStateMachine.decodeItem(
                    pipeline.store.stateGet(ApprovalsStateMachine.itemKey("rel-old")).orElseThrow());
            assertThat(item.status()).isEqualTo(ApprovalsStateMachine.STATUS_APPROVED);
        }
    }

    @Test
    void approvalsPayments_activateExactlyAtConfiguredHeight_withoutRetroactivePayload(
            @TempDir Path dir) {
        Map<String, String> approvalsSettings = Map.of(
                "machines.approvals.payments", "true",
                "machines.approvals.activations.payments", "2");
        ApprovalsStateMachine machine = new ApprovalsStateMachine(
                ApprovalsStateMachine.PaymentsConfig.from(approvalsSettings),
                ActivationSchedule.from(approvalsSettings, ApprovalsStateMachine.ID));
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, FX_SETTINGS)) {
            byte[] oldPayment = "old-payment".getBytes(StandardCharsets.UTF_8);
            FxKernel.Result before = pipeline.apply(
                    msg("t", ApprovalsStateMachine.propose("before", oldPayment, 1, 0), "alice"));
            assertThat(before.emitted()).isEmpty();

            byte[] activePayment = "active-payment".getBytes(StandardCharsets.UTF_8);
            FxKernel.Result atActivation = pipeline.apply(
                    msg("t", ApprovalsStateMachine.approve("before"), "alice"),
                    msg("t", ApprovalsStateMachine.propose("active", activePayment, 1, 0), "alice"),
                    msg("t", ApprovalsStateMachine.approve("active"), "alice"));

            assertThat(atActivation.emitted()).singleElement().satisfies(staged -> {
                assertThat(staged.record().scope()).isEqualTo("approvals/active");
                assertThat(staged.record().payload()).isEqualTo(activePayment);
            });
            var beforeItem = ApprovalsStateMachine.decodeItem(
                    pipeline.store.stateGet(ApprovalsStateMachine.itemKey("before")).orElseThrow());
            assertThat(beforeItem.status()).isEqualTo(ApprovalsStateMachine.STATUS_APPROVED);
            assertThat(pipeline.store.stateGet(ApprovalsStateMachine.effectLinkKey("before"))).isEmpty();
        }
    }

    @Test
    void approvalsPayments_isDeterministic_viaConformance() {
        StateMachineConformance.builder(
                        new com.bloxbean.cardano.yano.appchain.stdlib.StdlibStateMachineProviders
                                .ApprovalsProvider())
                .settings(Map.of(
                        "effects.enabled", "true",
                        "machines.approvals.payments", "true",
                        "machines.approvals.activations.payments", "1"))
                .blocks(12)
                .messagesPerBlock(2)
                .bodyGenerator((height, index, random) -> {
                    String itemId = "item-" + (height / 3);
                    return index == 0
                            ? ApprovalsStateMachine.propose(itemId,
                                    ("pay-" + itemId).getBytes(StandardCharsets.UTF_8), 1, 0)
                            : ApprovalsStateMachine.approve(itemId);
                })
                .runs(3)
                .assertDeterministic();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Emits one CHAIN effect per "emit-*" message and records onEffectResult calls. */
    private static final class RecordingMachine implements AppStateMachine {
        final long expiryBlocks;
        final ResultPolicy policy;
        final List<EffectResult> results = new ArrayList<>();

        RecordingMachine(long expiryBlocks) {
            this(expiryBlocks, ResultPolicy.CHAIN);
        }

        RecordingMachine(long expiryBlocks, ResultPolicy policy) {
            this.expiryBlocks = expiryBlocks;
            this.policy = policy;
        }

        @Override public String id() { return "recording"; }
        @Override public void apply(AppBlock block, AppStateWriter writer) { }

        @Override
        public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
            for (AppMessage message : block.messages()) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                if (message.getTopic().startsWith("~") || !body.startsWith("emit-")) {
                    continue;
                }
                effects.emit(EffectIntent.of("test.action", message.getBody())
                        .scope(body)
                        .result(policy)
                        .expiryBlocks(policy == ResultPolicy.CHAIN ? expiryBlocks : 0)
                        .build());
            }
        }

        @Override
        public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
            results.add(result);
            writer.put(("seen/" + result.effectId().canonical()).getBytes(StandardCharsets.UTF_8),
                    new byte[]{(byte) result.outcome().code()});
        }
    }

    private record Msg(String topic, byte[] body, String sender) {
    }

    private static Msg msg(String topic, String body) {
        return new Msg(topic, body.getBytes(StandardCharsets.UTF_8), "sender-a");
    }

    private static Msg msg(String topic, byte[] body) {
        return new Msg(topic, body, "sender-a");
    }

    private static Msg msg(String topic, byte[] body, String sender) {
        return new Msg(topic, body, sender);
    }

    /** Pipeline variant taking explicit messages (topics/bodies/senders). */
    private static final class MsgPipeline implements AutoCloseable {
        final AppLedgerStore store;
        final AppStateMachine machine;
        final FxKernel kernel;
        byte[] prevHash = AppBlock.GENESIS_PREV_HASH;
        long height;
        long senderSeq;

        MsgPipeline(Path dir, AppStateMachine machine, Map<String, String> settings) {
            this.store = new AppLedgerStore(dir.toString(), LoggerFactory.getLogger("fx-test"));
            this.machine = machine;
            this.kernel = new FxKernel(EffectsSettings.fromSettings(settings));
        }

        FxKernel.Result apply(Msg... msgs) {
            height++;
            List<AppMessage> list = new ArrayList<>();
            for (Msg msg : msgs) {
                byte[] sender = java.util.Arrays.copyOf(
                        msg.sender().getBytes(StandardCharsets.UTF_8), 32);
                long seq = ++senderSeq;
                byte[] id = AppMessage.computeMessageId("fx-chain", msg.topic(), sender, seq,
                        4_000_000_000L, msg.body());
                list.add(AppMessage.builder()
                        .messageId(id).chainId("fx-chain").topic(msg.topic()).sender(sender)
                        .senderSeq(seq).expiresAt(4_000_000_000L).body(msg.body())
                        .authScheme(0).authProof(new byte[64]).build());
            }
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "fx-chain", height, prevHash,
                    0, new byte[0], 1_700_000_000_000L + height * 1_000,
                    AppBlockCodec.messagesRoot(list), new byte[32], list,
                    new byte[32], FinalityCert.empty());
            FxBlockApplier.Applied applied = FxBlockApplier.applyAndCommit(store, kernel, machine, block);
            prevHash = applied.blockHash();
            return applied.fx();
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
