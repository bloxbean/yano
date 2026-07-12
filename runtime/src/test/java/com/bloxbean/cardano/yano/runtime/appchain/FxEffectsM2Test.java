package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FX-M2 execution plane (ADR app-layer/010 F5–F7, F9, F10): intake cursor,
 * finality gating, dispatch with per-effect retry/backoff, the PARKED lane,
 * backfill quarantine, SUBMITTED re-poll, retention, and the built-in webhook
 * executor.
 */
@Timeout(120)
class FxEffectsM2Test {

    private static final Map<String, String> FX_SETTINGS = Map.of(
            "effects.enabled", "true",
            "effects.max-per-block", "8",
            "effects.max-payload-bytes", "4096");

    private static EffectRuntime.Settings runtimeSettings(int maxAttempts) {
        return new EffectRuntime.Settings(true, Set.of(), 50, 2, maxAttempts, 1, 5, 0, 100);
    }

    // ------------------------------------------------------------------

    @Test
    void executesEffect_endToEnd_withIdempotencyKey(@TempDir Path dir) throws Exception {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed("ref-1".getBytes(StandardCharsets.UTF_8)));
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);

            assertThat(executor.invocations).hasSize(1);
            PendingEffect seen = executor.invocations.peek();
            assertThat(seen.idHash()).isEqualTo(seen.effectId().hash()); // key handed to executor
            assertThat(pipeline.store.fxQueueScan(10)).isEmpty();
            assertThat(runtime.stats().get("executed")).isEqualTo(1L);
        }
    }

    @Test
    void gatesL1AnchoredEffects_onAnchorHighWaterMark(@TempDir Path dir) throws Exception {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed(new byte[0]));
        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, FinalityGate.L1_ANCHORED), FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1);
            runtime.tick();
            Thread.sleep(100);
            assertThat(executor.invocations).isEmpty(); // anchor HWM = 0 < height 1

            pipeline.store.metaPutLong("anchor_last_height", 1);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            assertThat(executor.invocations).hasSize(1);
        }
    }

    @Test
    void retryableFailures_backOffThenPark_thenRequeueRecovers(@TempDir Path dir) throws Exception {
        AtomicReference<EffectExecution> outcome = new AtomicReference<>(
                EffectExecution.failed("boom", true));
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> outcome.get());
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(2), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1);

            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.RETRY);
            Thread.sleep(10); // backoff (1ms initial) elapses
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.PARKED); // attempt cap = 2
            assertThat(pipeline.store.fxQueueScan(10)).isEmpty();     // out of the queue — no
            assertThat(executor.invocations).hasSize(2);              // head-of-line blocking

            // Operator requeue with the target fixed
            outcome.set(EffectExecution.confirmed(new byte[0]));
            assertThat(runtime.requeue(1, 0)).isTrue();
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
        }
    }

    @Test
    void nonRetryableFailure_parksImmediately(@TempDir Path dir) throws Exception {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.failed("HTTP 400", false));
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(8), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.PARKED);
            assertThat(executor.invocations).hasSize(1); // no retries on definitive rejection
        }
    }

    @Test
    void submitted_isRepolledUntilConfirmed(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> calls.incrementAndGet() == 1
                        ? EffectExecution.submitted("tx-1".getBytes(StandardCharsets.UTF_8))
                        : EffectExecution.confirmed("tx-1".getBytes(StandardCharsets.UTF_8)));
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(8), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.SUBMITTED);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void lateEnabledExecutor_quarantinesHistory(@TempDir Path dir) throws Exception {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed(new byte[0]));
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS)) {
            pipeline.applyNext(1); // history BEFORE the executor exists
            pipeline.applyNext(1);
            try (EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
                runtime.tick();
                Thread.sleep(100);
                // Never blind-fires history (ADR-010 F10)
                assertThat(executor.invocations).isEmpty();
                assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().status())
                        .isEqualTo(FxStatusRecord.QUARANTINED);

                // Explicit operator decision executes it
                assertThat(runtime.requeue(1, 0)).isTrue();
                runtime.tick();
                awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);

                // NEW blocks after enablement flow normally
                pipeline.applyNext(1);
                runtime.tick();
                awaitStatus(pipeline.store, 3, 0, FxStatusRecord.DONE);
            }
        }
    }

    @Test
    void chainTerminalEffects_areNeverExecuted(@TempDir Path dir) throws Exception {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed(new byte[0]));
        // CHAIN effect expiring at height 3 — expired BEFORE the executor intakes it
        try (Pipeline pipeline = new Pipeline(dir, emittingWithExpiry("test.action", 2), FX_SETTINGS)) {
            pipeline.applyNext(1);
            pipeline.applyNext(0);
            pipeline.applyNext(0); // expiry sweep closes 1/0
            assertThat(pipeline.store.fxClosed(1, 0)).isTrue();
            try (EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
                runtime.tick();
                Thread.sleep(100);
                assertThat(executor.invocations).isEmpty();
            }
        }
    }

    @Test
    void retention_prunesResolvedKeepsOpen(@TempDir Path dir) throws Exception {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed(new byte[0]));
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1); // height 1 — will be DONE (resolved)
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            pipeline.applyNext(1); // height 2 — stays PENDING (open, un-executed NONE)

            // Horizon above both, but capped by the caller at the intake cursor
            // in production; here: prune below 2 → only height 1 goes
            int pruned = pipeline.store.fxPruneBelow(2);
            assertThat(pruned).isEqualTo(1);
            assertThat(pipeline.store.fxRecord(1, 0)).isEmpty();
            assertThat(pipeline.store.fxRecord(2, 0)).isPresent();
        }
    }

    @Test
    void webhookExecutor_postsWithIdempotencyHeaders(@TempDir Path dir) throws Exception {
        ConcurrentLinkedQueue<Map<String, String>> received = new ConcurrentLinkedQueue<>();
        AtomicInteger responseCode = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            Map<String, String> headers = Map.of(
                    "idempotency-key", exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    "effect-id", exchange.getRequestHeaders().getFirst("X-Effect-Id"),
                    "chain-id", exchange.getRequestHeaders().getFirst("X-App-Chain-Id"));
            received.add(headers);
            exchange.sendResponseHeaders(responseCode.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            WebhookEffectExecutor executor = new WebhookEffectExecutor(
                    Map.of("url", url), LoggerFactory.getLogger("fx"));
            PendingEffect effect = PendingEffect.of(
                    new com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord(1, "fx-chain",
                            7, 0, WebhookEffectExecutor.TYPE, "{\"x\":1}".getBytes(StandardCharsets.UTF_8),
                            "scope-1", FinalityGate.APP_FINAL, ResultPolicy.NONE, 0, null));

            EffectExecution ok = executor.execute(context("fx-chain"), effect);
            assertThat(ok).isInstanceOf(EffectExecution.Confirmed.class);
            Map<String, String> headers = received.poll();
            assertThat(headers.get("idempotency-key"))
                    .isEqualTo(com.bloxbean.cardano.yaci.core.util.HexUtil
                            .encodeHexString(effect.idHash()));
            assertThat(headers.get("effect-id")).isEqualTo("fx-chain/7/0");
            assertThat(headers.get("chain-id")).isEqualTo("fx-chain");

            responseCode.set(400); // definitive rejection → non-retryable
            EffectExecution rejected = executor.execute(context("fx-chain"), effect);
            assertThat(rejected).isInstanceOf(EffectExecution.Failed.class);
            assertThat(((EffectExecution.Failed) rejected).retryable()).isFalse();

            responseCode.set(503); // transient → retryable
            EffectExecution transientFail = executor.execute(context("fx-chain"), effect);
            assertThat(((EffectExecution.Failed) transientFail).retryable()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static AppStateMachine emitting(String type, ResultPolicy policy, FinalityGate gate) {
        return new AppStateMachine() {
            @Override public String id() { return "emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                for (AppMessage message : block.messages()) {
                    effects.emit(EffectIntent.of(type, message.getBody())
                            .scope("s/" + block.height())
                            .result(policy)
                            .gate(gate != null ? gate : FinalityGate.CHAIN_DEFAULT)
                            .build());
                }
            }
        };
    }

    private static AppStateMachine emittingWithExpiry(String type, long expiryBlocks) {
        return new AppStateMachine() {
            @Override public String id() { return "emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                for (AppMessage message : block.messages()) {
                    effects.emit(EffectIntent.of(type, message.getBody())
                            .result(ResultPolicy.CHAIN)
                            .expiryBlocks(expiryBlocks)
                            .build());
                }
            }
        };
    }

    private interface Behavior {
        EffectExecution run(PendingEffect effect);
    }

    private static final class RecordingExecutor implements AppEffectExecutor {
        final String type;
        final Behavior behavior;
        final ConcurrentLinkedQueue<PendingEffect> invocations = new ConcurrentLinkedQueue<>();

        RecordingExecutor(String type, Behavior behavior) {
            this.type = type;
            this.behavior = behavior;
        }

        @Override public String id() { return "recording"; }
        @Override public boolean supports(String effectType) { return type.equals(effectType); }

        @Override
        public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
            invocations.add(effect);
            return behavior.run(effect);
        }
    }

    private static EffectExecutionContext context(String chainId) {
        return new EffectExecutionContext() {
            @Override public String chainId() { return chainId; }
            @Override public long tipHeight() { return 0; }
            @Override public long anchoredHeight() { return 0; }
            @Override public int attempt() { return 1; }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }

    private static void awaitStatus(AppLedgerStore store, long height, int ordinal, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer status = store.fxRuntimeStatus(height, ordinal)
                    .map(FxStatusRecord::status).orElse(null);
            if (status != null && status == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Effect " + height + "/" + ordinal + " never reached status "
                + expected + "; current: " + store.fxRuntimeStatus(height, ordinal)
                        .map(FxStatusRecord::statusName).orElse("<none>"));
    }

    /** Same shared pipeline as the M1 tests (FxBlockApplier). */
    private static final class Pipeline implements AutoCloseable {
        final AppLedgerStore store;
        final AppStateMachine machine;
        final FxKernel kernel;
        byte[] prevHash = AppBlock.GENESIS_PREV_HASH;
        long height;
        long senderSeq;

        Pipeline(Path dir, AppStateMachine machine, Map<String, String> settings) {
            this.store = new AppLedgerStore(dir.toString(), LoggerFactory.getLogger("fx-test"));
            this.machine = machine;
            this.kernel = new FxKernel(EffectsSettings.fromSettings(settings));
        }

        FxKernel.Result applyNext(int messages) {
            height++;
            byte[] sender = new byte[32];
            List<AppMessage> list = new ArrayList<>();
            for (int i = 0; i < messages; i++) {
                byte[] body = ("body-" + height + "-" + i).getBytes(StandardCharsets.UTF_8);
                long seq = ++senderSeq;
                byte[] id = AppMessage.computeMessageId("fx-chain", "t", sender, seq,
                        4_000_000_000L, body);
                list.add(AppMessage.builder()
                        .messageId(id).chainId("fx-chain").topic("t").sender(sender)
                        .senderSeq(seq).expiresAt(4_000_000_000L).body(body)
                        .authScheme(0).authProof(new byte[64]).build());
            }
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "fx-chain", height, prevHash,
                    0, new byte[0], 1_700_000_000_000L + height * 1_000,
                    AppBlockCodec.messagesRoot(list), new byte[32], list, sender,
                    FinalityCert.empty());
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
