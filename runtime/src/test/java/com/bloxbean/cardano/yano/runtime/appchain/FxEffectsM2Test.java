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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
            assertThat(map(runtime.stats().get("statusCounts")))
                    .containsEntry("PARKED", 1L);
            assertThat(map(runtime.stats().get("executionTotals")))
                    .containsEntry("parked", 1L);

            // Operator requeue with the target fixed
            outcome.set(EffectExecution.confirmed(new byte[0]));
            assertThat(runtime.requeue(1, 0)).isTrue();
            assertThat(map(runtime.stats().get("statusCounts")))
                    .containsEntry("PARKED", 0L)
                    .containsEntry("PENDING", 1L);
            assertThat(runtime.stats()).containsEntry("queueDepth", 1L);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
        }
    }

    @Test
    void statsScan_isExactBeyondDispatchScanLimit_andCardinalityIsBounded(@TempDir Path dir) {
        Map<String, String> manySettings = new java.util.LinkedHashMap<>(FX_SETTINGS);
        manySettings.put("effects.max-per-block", "5000");
        EffectRuntime.Settings settings = new EffectRuntime.Settings(true, Set.of(),
                50, 2, 3, 1, 5, 0, 100, Set.of("test.action"));
        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), manySettings);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain", settings,
                     List.of(), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(4_100); // scanLimit is 4096
            runtime.tick();

            Map<String, Object> stats = runtime.stats();
            assertThat(stats).containsEntry("queueDepth", 4_100L);
            assertThat(map(stats.get("statusCounts")))
                    .containsEntry("PENDING", 4_100L);
            assertThat(map(stats.get("oldestPending")))
                    .containsEntry("height", 1L)
                    .containsEntry("ageBlocks", 0L);
            assertThat(map(stats.get("resultBacklogByType")).keySet())
                    .containsExactlyInAnyOrder("test.action", "other");
            assertThat(map(stats.get("latencyByType")).keySet())
                    .containsExactlyInAnyOrder("test.action", "other");
        }
    }

    @Test
    void boundedQueueScans_roundRobinPastIneligiblePrefix(@TempDir Path dir) throws Exception {
        Map<String, String> manySettings = new java.util.LinkedHashMap<>(FX_SETTINGS);
        manySettings.put("effects.max-per-block", "5000");

        RecordingExecutor executor = new RecordingExecutor("target.action",
                effect -> EffectExecution.confirmed(new byte[0]));
        try (Pipeline pipeline = new Pipeline(dir.resolve("dispatch"),
                emittingOnlyTailAsTarget(), manySettings);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), log())) {
            pipeline.applyNext(4_100); // first 4,096 rows have no supporting executor
            runtime.tick();
            assertThat(executor.invocations).isEmpty();
            runtime.tick();
            awaitStatus(pipeline.store, 1, 4096, FxStatusRecord.DONE);
            assertThat(executor.invocations).singleElement()
                    .satisfies(effect -> assertThat(effect.effectId().ordinal()).isEqualTo(4096));
        }

        try (Pipeline pipeline = new Pipeline(dir.resolve("claim"),
                emittingOnlyTailAsTarget(), manySettings);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(), Map.of(), log())) {
            pipeline.applyNext(4_100);
            runtime.tick(); // intake only; external workers claim from the queue
            assertThat(runtime.claim("worker", Set.of("target.action"), 1, 60)).isEmpty();
            assertThat(runtime.claim("worker", Set.of("target.action"), 1, 60))
                    .singleElement()
                    .satisfies(effect -> assertThat(effect.effectId().ordinal()).isEqualTo(4096));
        }
    }

    @Test
    void dispatchCursor_doesNotSkipCapLimitedTailUnderSustainedArrival(@TempDir Path dir)
            throws Exception {
        Map<String, String> manySettings = new java.util.LinkedHashMap<>(FX_SETTINGS);
        manySettings.put("effects.max-per-block", "5000");
        EffectRuntime.Settings oneWorker = new EffectRuntime.Settings(true, Set.of(),
                50, 1, 3, 1, 5, 0, 100);
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed(new byte[0]));

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), manySettings);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain", oneWorker,
                     List.of(executor), Map.of(), log())) {
            pipeline.applyNext(4_100); // larger than the 4,096-row scan window
            runtime.tick();            // dispatch cap = two rows
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            awaitStatus(pipeline.store, 1, 1, FxStatusRecord.DONE);
            awaitInFlight(runtime, 0);

            // Keep more than a full scan window after the old cursor. A scan-
            // end cursor would follow the arriving head forever and strand
            // 1/2; the processed-prefix cursor resumes exactly at that row.
            pipeline.applyNext(4_100);
            assertThat(pipeline.store.fxQueueScanAll()).hasSizeGreaterThan(4_096);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 2, FxStatusRecord.DONE);

            assertThat(executor.invocations).anySatisfy(effect -> {
                assertThat(effect.effectId().height()).isEqualTo(1);
                assertThat(effect.effectId().ordinal()).isEqualTo(2);
            });
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
            assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().lastError())
                    .isEqualTo("HTTP 400");
            assertThat(runtime.statusOf(1, 0).orElseThrow())
                    .containsEntry("lastError", "HTTP 400");
        }
    }

    @Test
    void thrownExecutorFailureRetainsOnlyBoundedClassName(@TempDir Path dir) throws Exception {
        String secret = "api-token=must-not-leak";
        RecordingExecutor executor = new RecordingExecutor("test.action", effect -> {
            throw new IllegalStateException(secret);
        });
        String errorType = IllegalStateException.class.getName();

        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(1), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.PARKED);

            FxStatusRecord persisted = pipeline.store.fxRuntimeStatus(1, 0).orElseThrow();
            assertThat(persisted.lastError()).isEqualTo(errorType).doesNotContain(secret);
            assertThat(runtime.statusOf(1, 0).orElseThrow())
                    .containsEntry("lastError", errorType)
                    .doesNotContainValue(secret);
            assertThat(runtime.stats())
                    .containsEntry("lastError", errorType)
                    .doesNotContainValue(secret);
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
    void queuedWork_isGloballyBound_andRevalidatesStatusBeforeExecution(@TempDir Path dir)
            throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        RecordingExecutor executor = new RecordingExecutor("test.action", effect -> {
            if (effect.effectId().ordinal() == 0) {
                firstStarted.countDown();
                awaitLatch(releaseFirst, "release first effect");
            }
            return EffectExecution.confirmed(new byte[0]);
        });
        EffectRuntime.Settings oneWorker = new EffectRuntime.Settings(true, Set.of(),
                50, 1, 3, 1, 5, 0, 100);
        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null),
                Map.of("effects.enabled", "true", "effects.max-per-block", "16",
                        "effects.max-payload-bytes", "4096"));
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain", oneWorker,
                     List.of(executor), Map.of(), log())) {
            pipeline.applyNext(10);
            runtime.tick();
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // maxParallel=1 allows one running + one queued, globally. More
            // ticks cannot accumulate work in the executor's queue.
            assertThat(runtime.stats()).containsEntry("inFlight", 2);
            for (int i = 0; i < 5; i++) {
                runtime.tick();
            }
            assertThat(runtime.stats()).containsEntry("inFlight", 2);
            assertThat(executor.invocations).hasSize(1);

            // Simulate an operator/runtime terminal transition while ordinal
            // 1 is waiting in the bounded pool. Its stale scheduling snapshot
            // must not authorize an external call.
            pipeline.store.fxRuntimeComplete(1, 1,
                    FxStatusRecord.pending().parked("operator parked"), false);
            releaseFirst.countDown();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            awaitInFlight(runtime, 0);

            assertThat(executor.invocations).singleElement()
                    .satisfies(effect -> assertThat(effect.effectId().ordinal()).isZero());
            assertThat(pipeline.store.fxRuntimeStatus(1, 1).orElseThrow().status())
                    .isEqualTo(FxStatusRecord.PARKED);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void queuedWork_rechecksExpiryBeforeExecution(@TempDir Path dir)
            throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        RecordingExecutor executor = new RecordingExecutor("test.action", effect -> {
            if (effect.effectId().ordinal() == 0) {
                firstStarted.countDown();
                awaitLatch(releaseFirst, "release first effect");
            }
            return EffectExecution.confirmed(new byte[0]);
        });
        EffectRuntime.Settings oneWorker = new EffectRuntime.Settings(true, Set.of(),
                50, 1, 3, 1, 5, 0, 100);
        try (Pipeline pipeline = new Pipeline(dir, emittingWithExpiry("test.action", 3),
                Map.of("effects.enabled", "true", "effects.max-per-block", "8",
                        "effects.max-payload-bytes", "4096"));
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain", oneWorker,
                     List.of(executor), Map.of(), log())) {
            pipeline.applyNext(2); // expiry height 4; safe to dispatch at tip 1
            runtime.tick();
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            pipeline.applyNext(0); // tip 2: ordinal 1 is now inside the safety window
            releaseFirst.countDown();
            awaitInFlight(runtime, 0);
            assertThat(executor.invocations).singleElement()
                    .satisfies(effect -> assertThat(effect.effectId().ordinal()).isZero());
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void queuedWork_rechecksChainClosureBeforeExecution(@TempDir Path dir)
            throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        RecordingExecutor executor = new RecordingExecutor("test.action", effect -> {
            if (effect.effectId().ordinal() == 0) {
                firstStarted.countDown();
                awaitLatch(releaseFirst, "release first effect");
            }
            return EffectExecution.confirmed(new byte[0]);
        });
        EffectRuntime.Settings oneWorker = new EffectRuntime.Settings(true, Set.of(),
                50, 1, 3, 1, 5, 0, 100);
        try (Pipeline pipeline = new Pipeline(dir, emittingWithExpiry("test.action", 3),
                Map.of("effects.enabled", "true", "effects.max-per-block", "8",
                        "effects.max-payload-bytes", "4096"));
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain", oneWorker,
                     List.of(executor), Map.of(), log())) {
            pipeline.applyNext(2);
            runtime.tick();
            assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Ordinal 1 is already queued, but expires on-chain while ordinal
            // 0 occupies the only worker. The queued call must be suppressed.
            pipeline.applyNext(0);
            pipeline.applyNext(0);
            pipeline.applyNext(0);
            assertThat(pipeline.store.fxClosed(1, 1)).isTrue();
            releaseFirst.countDown();
            awaitInFlight(runtime, 0);

            assertThat(executor.invocations).singleElement()
                    .satisfies(effect -> assertThat(effect.effectId().ordinal()).isZero());
            assertThat(pipeline.store.fxQueueExists(1, 1)).isFalse();
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void nonFatalSupportsError_doesNotCancelRecurringDispatch(@TempDir Path dir)
            throws Exception {
        AtomicInteger supportsCalls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "flaky-supports"; }

            @Override
            public boolean supports(String effectType) {
                if (supportsCalls.getAndIncrement() == 0) {
                    throw new LinkageError("transient plugin linkage failure");
                }
                return "test.action".equals(effectType);
            }

            @Override
            public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
                executions.incrementAndGet();
                return EffectExecution.confirmed(new byte[0]);
            }
        };
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), log())) {
            pipeline.applyNext(1);
            try {
                scheduler.scheduleWithFixedDelay(runtime::tick, 0, 5, TimeUnit.MILLISECONDS);
                awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            } finally {
                scheduler.shutdownNow();
                assertThat(scheduler.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(supportsCalls).hasValueGreaterThanOrEqualTo(3);
            assertThat(executions).hasValue(1);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void nonFatalExecuteError_isRecordedAsRetryAndCanRecover(@TempDir Path dir)
            throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "flaky-execute"; }
            @Override public boolean supports(String effectType) {
                return "test.action".equals(effectType);
            }

            @Override
            public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
                if (executions.getAndIncrement() == 0) {
                    throw new AssertionError("executor assertion");
                }
                return EffectExecution.confirmed(new byte[0]);
            }
        };

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), log())) {
            pipeline.applyNext(1);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.RETRY);
            assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().lastError())
                    .isEqualTo(AssertionError.class.getName())
                    .doesNotContain("executor assertion");

            Thread.sleep(10);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            assertThat(executions).hasValue(2);
        }
    }

    @Test
    void fatalVmError_isReportedAndRethrown(@TempDir Path dir) {
        TestVirtualMachineError failure = new TestVirtualMachineError("fatal plugin failure");
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "fatal-supports"; }
            @Override public boolean supports(String effectType) { throw failure; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
        };

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), log())) {
            pipeline.applyNext(1);

            assertThatThrownBy(runtime::tick).isSameAs(failure);
            assertThat(runtime.stats().get("lastError").toString())
                    .isEqualTo(TestVirtualMachineError.class.getName())
                    .doesNotContain("fatal plugin failure");
        }
    }

    @Test
    void closeDoesNotWaitForBlockedSupports_andPreventsItsDispatch(@TempDir Path dir)
            throws Exception {
        CountDownLatch supportsStarted = new CountDownLatch(1);
        CountDownLatch releaseSupports = new CountDownLatch(1);
        CountDownLatch executorClosed = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "blocking-supports"; }

            @Override
            public boolean supports(String effectType) {
                supportsStarted.countDown();
                awaitLatch(releaseSupports, "release supports check");
                return true;
            }

            @Override
            public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
                executions.incrementAndGet();
                return EffectExecution.confirmed(new byte[0]);
            }

            @Override
            public void close() {
                executorClosed.countDown();
            }
        };

        Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS);
        EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                runtimeSettings(3), List.of(executor), Map.of(), log());
        boolean ledgerClosed = false;
        try {
            pipeline.applyNext(1);
            AtomicReference<Throwable> tickFailure = new AtomicReference<>();
            Thread ticker = new Thread(() -> {
                try {
                    runtime.tick();
                } catch (Throwable t) {
                    tickFailure.set(t);
                }
            }, "fx-test-tick");
            CountDownLatch closeReturned = new CountDownLatch(1);
            Thread closer = new Thread(() -> {
                runtime.close(100, 100);
                closeReturned.countDown();
            }, "fx-test-close");
            try {
                ticker.start();
                assertThat(supportsStarted.await(5, TimeUnit.SECONDS)).isTrue();
                closer.start();
                awaitClosed(runtime);

                // supports() runs outside the scheduler-ledger barrier. A
                // non-cooperative plugin cannot make shutdown unbounded, but
                // its executor is not closed underneath the live callback.
                assertThat(closeReturned.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executorClosed.getCount()).isEqualTo(1);
                CompletableFuture<Void> closeCompletion =
                        runtime.closeCompletion().toCompletableFuture();
                assertThat(closeCompletion.isDone()).isFalse();

                pipeline.close();
                ledgerClosed = true;
                releaseSupports.countDown();
                ticker.join(5_000);

                assertThat(ticker.isAlive()).isFalse();
                assertThat(tickFailure.get()).isNull();
                assertThat(executions).hasValue(0);
                assertThat(executorClosed.await(5, TimeUnit.SECONDS)).isTrue();
                closeCompletion.get(5, TimeUnit.SECONDS);
            } finally {
                releaseSupports.countDown();
                runtime.close(0, 0);
                ticker.join(5_000);
                closer.join(5_000);
            }
        } finally {
            releaseSupports.countDown();
            runtime.close(0, 0);
            if (!ledgerClosed) {
                pipeline.close();
            }
        }
    }

    @Test
    void closeFansOutExecutorCleanup_andJoinsCooperativeClose(@TempDir Path dir)
            throws Exception {
        CountDownLatch blockingCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockingClose = new CountDownLatch(1);
        CountDownLatch blockingCloseFinished = new CountDownLatch(1);
        CountDownLatch cooperativeCloseFinished = new CountDownLatch(1);
        AtomicInteger blockingCloseCalls = new AtomicInteger();
        AtomicInteger cooperativeCloseCalls = new AtomicInteger();
        AppEffectExecutor blocking = new AppEffectExecutor() {
            @Override public String id() { return "blocking-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }

            @Override
            public void close() {
                blockingCloseCalls.incrementAndGet();
                blockingCloseStarted.countDown();
                while (true) {
                    try {
                        releaseBlockingClose.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // Deliberately non-cooperative plugin cleanup.
                    }
                }
                blockingCloseFinished.countDown();
            }
        };
        AppEffectExecutor cooperative = new AppEffectExecutor() {
            @Override public String id() { return "cooperative-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }

            @Override
            public void close() {
                cooperativeCloseCalls.incrementAndGet();
                cooperativeCloseFinished.countDown();
            }
        };

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS)) {
            EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(blocking, cooperative), Map.of(), log());
            CountDownLatch runtimeCloseReturned = new CountDownLatch(1);
            AtomicBoolean cooperativeClosedAtReturn = new AtomicBoolean();
            Thread runtimeCloser = new Thread(() -> {
                runtime.close(25, 25);
                cooperativeClosedAtReturn.set(cooperativeCloseFinished.getCount() == 0);
                runtimeCloseReturned.countDown();
            }, "fx-test-runtime-close");
            try {
                runtimeCloser.start();
                assertThat(blockingCloseStarted.await(5, TimeUnit.SECONDS)).isTrue();

                // Independent cleanup tasks let the cooperative executor
                // close even though the first plugin is still blocked.
                assertThat(cooperativeCloseFinished.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(blockingCloseFinished.getCount()).isEqualTo(1);

                // Runtime close waits only the shared short join budget. The
                // cooperative resource is normally released before return;
                // the blocked daemon remains safely detached.
                assertThat(runtimeCloseReturned.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(cooperativeClosedAtReturn).isTrue();
                assertThat(blockingCloseFinished.getCount()).isEqualTo(1);
                CompletableFuture<Void> closeCompletion =
                        runtime.closeCompletion().toCompletableFuture();
                assertThat(closeCompletion.isDone()).isFalse();

                runtime.close(0, 0); // idempotent: never schedules a second callback
                releaseBlockingClose.countDown();
                assertThat(blockingCloseFinished.await(5, TimeUnit.SECONDS)).isTrue();
                closeCompletion.get(5, TimeUnit.SECONDS);
                assertThat(blockingCloseCalls).hasValue(1);
                assertThat(cooperativeCloseCalls).hasValue(1);
            } finally {
                releaseBlockingClose.countDown();
                runtimeCloser.join(5_000);
                runtime.close(0, 0);
            }
        }
    }

    @Test
    void closeCompletion_reportsThrowableAfterAllCloseCallbacksEnd(@TempDir Path dir)
            throws Exception {
        IllegalStateException ordinaryFailure =
                new IllegalStateException("ordinary close failure");
        AssertionError closeFailure = new AssertionError("close assertion");
        AtomicInteger healthyCloseCalls = new AtomicInteger();
        AppEffectExecutor ordinaryFailing = new AppEffectExecutor() {
            @Override public String id() { return "ordinary-failing-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() { throw ordinaryFailure; }
        };
        AppEffectExecutor failing = new AppEffectExecutor() {
            @Override public String id() { return "failing-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() { throw closeFailure; }
        };
        AppEffectExecutor healthy = new AppEffectExecutor() {
            @Override public String id() { return "healthy-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() { healthyCloseCalls.incrementAndGet(); }
        };

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS)) {
            EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(ordinaryFailing, failing, healthy), Map.of(), log());
            try {
                runtime.close(0, 0);
                CompletableFuture<Void> closeCompletion =
                        runtime.closeCompletion().toCompletableFuture();

                assertThatThrownBy(() -> closeCompletion.get(5, TimeUnit.SECONDS))
                        .hasCause(closeFailure);
                assertThat(closeFailure.getSuppressed()).containsExactly(ordinaryFailure);
                assertThat(healthyCloseCalls).hasValue(1);
            } finally {
                runtime.close(0, 0);
            }
        }
    }

    @Test
    void cleanupCoordinatorStartFailureClosesProductsBeforeLifetimeAndFatalEscape(
            @TempDir Path dir
    ) {
        TestVirtualMachineError fatal = new TestVirtualMachineError("coordinator start");
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicBoolean lifetimeDoneInsideClose = new AtomicBoolean();
        AtomicReference<CompletableFuture<Void>> lifetime = new AtomicReference<>();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "coordinator-fallback"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() {
                closeCalls.incrementAndGet();
                lifetimeDoneInsideClose.set(lifetime.get().isDone());
            }
        };
        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS)) {
            EffectRuntime runtime = new EffectRuntime(
                    pipeline.store,
                    "fx-chain",
                    runtimeSettings(1),
                    List.of(executor),
                    Map.of(),
                    "coordinator-start-owner",
                    log(),
                    task -> { throw fatal; });
            CompletableFuture<Void> completion = runtime.closeCompletion().toCompletableFuture();
            lifetime.set(completion);

            assertThatThrownBy(() -> runtime.close(0, 0)).isSameAs(fatal);
            assertThat(completion).isCompletedExceptionally();
            assertThatThrownBy(completion::join).hasCause(fatal);
            assertThat(closeCalls).hasValue(1);
            assertThat(lifetimeDoneInsideClose).isFalse();
        }
    }

    @Test
    void executorCloseTaskStartFailureFallsBackWithoutOutrunningAnyProductLifetime(
            @TempDir Path dir
    ) throws Exception {
        AssertionError startFailure = new AssertionError("executor close start");
        CountDownLatch fallbackCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseFallbackClose = new CountDownLatch(1);
        CountDownLatch parallelCloseFinished = new CountDownLatch(1);
        AtomicInteger fallbackCloseCalls = new AtomicInteger();
        AtomicInteger parallelCloseCalls = new AtomicInteger();
        AppEffectExecutor fallback = new AppEffectExecutor() {
            @Override public String id() { return "failed-close-handoff"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() {
                fallbackCloseCalls.incrementAndGet();
                fallbackCloseStarted.countDown();
                awaitLatch(releaseFallbackClose, "release fallback executor close");
            }
        };
        AppEffectExecutor parallel = new AppEffectExecutor() {
            @Override public String id() { return "parallel-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() {
                parallelCloseCalls.incrementAndGet();
                parallelCloseFinished.countDown();
            }
        };
        AtomicInteger closeThreadIndex = new AtomicInteger();

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS)) {
            EffectRuntime runtime = new EffectRuntime(
                    pipeline.store,
                    "fx-chain",
                    runtimeSettings(1),
                    List.of(fallback, parallel),
                    Map.of(),
                    "executor-task-start-owner",
                    log(),
                    task -> Thread.ofPlatform().daemon(true).unstarted(task),
                    task -> {
                        if (closeThreadIndex.getAndIncrement() == 0) {
                            return new Thread(task) {
                                @Override
                                public synchronized void start() {
                                    throw startFailure;
                                }
                            };
                        }
                        return Thread.ofPlatform().daemon(true).unstarted(task);
                    });
            CompletableFuture<Void> completion = runtime.closeCompletion().toCompletableFuture();
            try {
                runtime.close(0, 0);
                assertThat(fallbackCloseStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(parallelCloseFinished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(completion.isDone()).isFalse();

                releaseFallbackClose.countDown();
                assertThatThrownBy(() -> completion.get(5, TimeUnit.SECONDS))
                        .hasCause(startFailure);
                assertThat(fallbackCloseCalls).hasValue(1);
                assertThat(parallelCloseCalls).hasValue(1);
            } finally {
                releaseFallbackClose.countDown();
                runtime.close(0, 0);
            }
        }
    }

    @Test
    void cleanupDiagnosticFatalCannotPublishLifetimeBeforeEveryActualCloseEnds(
            @TempDir Path dir
    ) throws Exception {
        IllegalStateException startFailure = new IllegalStateException("close task start");
        TestVirtualMachineError diagnosticFatal =
                new TestVirtualMachineError("cleanup diagnostic");
        CountDownLatch blockingCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockingClose = new CountDownLatch(1);
        CountDownLatch fatalEscaped = new CountDownLatch(1);
        AtomicInteger blockingCloseCalls = new AtomicInteger();
        AtomicInteger parallelCloseCalls = new AtomicInteger();
        AtomicReference<Throwable> escapedFailure = new AtomicReference<>();
        AtomicBoolean lifetimeDoneAtFatalEscape = new AtomicBoolean();
        AtomicReference<CompletableFuture<Void>> lifetime = new AtomicReference<>();
        AppEffectExecutor blocking = new AppEffectExecutor() {
            @Override public String id() { return "diagnostic-blocking-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() {
                blockingCloseCalls.incrementAndGet();
                blockingCloseStarted.countDown();
                awaitLatch(releaseBlockingClose, "release diagnostic close");
            }
        };
        AppEffectExecutor parallel = new AppEffectExecutor() {
            @Override public String id() { return "diagnostic-parallel-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() { parallelCloseCalls.incrementAndGet(); }
        };
        org.slf4j.Logger failingLogger = mock(org.slf4j.Logger.class, invocation -> {
            if ("warn".equals(invocation.getMethod().getName())) {
                throw diagnosticFatal;
            }
            return null;
        });
        AtomicInteger closeThreadIndex = new AtomicInteger();

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS)) {
            EffectRuntime runtime = new EffectRuntime(
                    pipeline.store,
                    "fx-chain",
                    runtimeSettings(1),
                    List.of(blocking, parallel),
                    Map.of(),
                    "diagnostic-lifetime-owner",
                    failingLogger,
                    task -> {
                        Thread thread = new Thread(task, "diagnostic-cleanup-coordinator");
                        thread.setDaemon(true);
                        thread.setUncaughtExceptionHandler((ignored, failure) -> {
                            escapedFailure.set(failure);
                            lifetimeDoneAtFatalEscape.set(lifetime.get().isDone());
                            fatalEscaped.countDown();
                        });
                        return thread;
                    },
                    task -> {
                        if (closeThreadIndex.getAndIncrement() == 0) {
                            return new Thread(task) {
                                @Override
                                public synchronized void start() {
                                    throw startFailure;
                                }
                            };
                        }
                        return Thread.ofPlatform().daemon(true).unstarted(task);
                    });
            CompletableFuture<Void> completion = runtime.closeCompletion().toCompletableFuture();
            lifetime.set(completion);
            try {
                runtime.close(0, 0);
                assertThat(blockingCloseStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(completion.isDone()).isFalse();
                assertThat(fatalEscaped.getCount()).isEqualTo(1);
                assertThat(parallelCloseCalls).hasValue(1);

                releaseBlockingClose.countDown();
                assertThat(fatalEscaped.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(escapedFailure).hasValue(diagnosticFatal);
                assertThat(lifetimeDoneAtFatalEscape).isTrue();
                assertThatThrownBy(completion::join).hasCause(diagnosticFatal);
                assertThat(blockingCloseCalls).hasValue(1);
                assertThat(parallelCloseCalls).hasValue(1);
            } finally {
                releaseBlockingClose.countDown();
                runtime.close(0, 0);
            }
        }
    }

    @Test
    void synchronousCoordinatorFallbackAllowsExecutorCloseToReenterRuntimeClose(
            @TempDir Path dir
    ) throws Exception {
        TestVirtualMachineError coordinatorFailure =
                new TestVirtualMachineError("coordinator handoff");
        AtomicReference<EffectRuntime> runtimeRef = new AtomicReference<>();
        AtomicInteger closeCalls = new AtomicInteger();
        CountDownLatch reentrantCloseReturned = new CountDownLatch(1);
        CountDownLatch outerCloseReturned = new CountDownLatch(1);
        AtomicReference<Throwable> outerFailure = new AtomicReference<>();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "reentrant-runtime-close"; }
            @Override public boolean supports(String effectType) { return false; }
            @Override public EffectExecution execute(
                    EffectExecutionContext context, PendingEffect effect) {
                throw new AssertionError("not executed");
            }
            @Override public void close() {
                closeCalls.incrementAndGet();
                runtimeRef.get().close(0, 0);
                reentrantCloseReturned.countDown();
            }
        };

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS)) {
            EffectRuntime runtime = new EffectRuntime(
                    pipeline.store,
                    "fx-chain",
                    runtimeSettings(1),
                    List.of(executor),
                    Map.of(),
                    "reentrant-close-owner",
                    log(),
                    task -> { throw coordinatorFailure; });
            runtimeRef.set(runtime);
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    runtime.close(0, 0);
                } catch (Throwable failure) {
                    outerFailure.set(failure);
                } finally {
                    outerCloseReturned.countDown();
                }
            });
            try {
                assertThat(reentrantCloseReturned.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(outerCloseReturned.await(5, TimeUnit.SECONDS)).isTrue();
                closer.join(5_000);
                assertThat(closer.isAlive()).isFalse();
                assertThat(outerFailure).hasValue(coordinatorFailure);
                assertThat(closeCalls).hasValue(1);
                assertThat(runtime.closeCompletion().toCompletableFuture())
                        .isCompletedExceptionally();
            } finally {
                closer.interrupt();
                closer.join(5_000);
                runtime.close(0, 0);
            }
        }
    }

    @Test
    void cleanupAggregationPromotesProcessFatalAboveEarlierAssertion() {
        AssertionError assertion = new AssertionError("assertion");
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal cleanup");

        Throwable outcome = EffectRuntime.addFailure(assertion, fatal);

        assertThat(outcome).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(assertion);
    }

    @Test
    void close_waitsForAcceptedApiOperationBeforeLedgerTeardown(@TempDir Path dir)
            throws Exception {
        CountDownLatch filterStarted = new CountDownLatch(1);
        CountDownLatch releaseFilter = new CountDownLatch(1);
        Set<String> blockingTypes = new java.util.AbstractSet<>() {
            @Override
            public java.util.Iterator<String> iterator() {
                return Set.of("test.action").iterator();
            }

            @Override public int size() { return 1; }

            @Override
            public boolean contains(Object value) {
                filterStarted.countDown();
                awaitLatch(releaseFilter, "release claim type filter");
                return "test.action".equals(value);
            }
        };

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS)) {
            EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(), Map.of(), log());
            pipeline.applyNext(1);
            runtime.tick();

            AtomicReference<Throwable> claimFailure = new AtomicReference<>();
            AtomicReference<List<PendingEffect>> claimResult = new AtomicReference<>();
            Thread claimant = new Thread(() -> {
                try {
                    claimResult.set(runtime.claim("worker", blockingTypes, 1, 60));
                } catch (Throwable t) {
                    claimFailure.set(t);
                }
            }, "fx-test-claim");
            CountDownLatch closeReturned = new CountDownLatch(1);
            Thread closer = new Thread(() -> {
                runtime.close(100, 100);
                closeReturned.countDown();
            }, "fx-test-api-close");
            try {
                claimant.start();
                assertThat(filterStarted.await(5, TimeUnit.SECONDS)).isTrue();
                closer.start();
                awaitClosed(runtime);

                // The accepted claim still has ledger work after the blocked
                // filter. close must cross the API write barrier before the
                // subsystem is allowed to close the store.
                assertThat(closeReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
                releaseFilter.countDown();
                assertThat(closeReturned.await(5, TimeUnit.SECONDS)).isTrue();
                claimant.join(5_000);

                assertThat(claimant.isAlive()).isFalse();
                assertThat(claimFailure.get()).isNull();
                assertThat(claimResult.get()).isEmpty();
                assertThat(runtime.statusOf(1, 0)).isEmpty();
                assertThat(runtime.pendingInjections(1, 0)).isEmpty();
                assertThat(runtime.report("worker", 1, 0, true, null, null)).isFalse();
                assertThat(runtime.requeue(1, 0)).isFalse();
            } finally {
                releaseFilter.countDown();
                runtime.close(0, 0);
                claimant.join(5_000);
                closer.join(5_000);
            }
        }
    }

    @Test
    void interruptResistantCall_usesSnapshotContextAndDefersExecutorClose(@TempDir Path dir)
            throws Exception {
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        CountDownLatch executionFinished = new CountDownLatch(1);
        CountDownLatch executorClosed = new CountDownLatch(1);
        AtomicLong observedTip = new AtomicLong(-1);
        AtomicLong observedAnchor = new AtomicLong(-1);
        AtomicReference<Throwable> contextFailure = new AtomicReference<>();
        AtomicBoolean callActiveWhenClosed = new AtomicBoolean();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "interrupt-resistant"; }
            @Override public boolean supports(String effectType) {
                return "test.action".equals(effectType);
            }

            @Override
            public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
                executionStarted.countDown();
                while (true) {
                    try {
                        releaseExecution.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // Deliberately non-cooperative: exercise bounded close.
                    }
                }
                try {
                    observedTip.set(context.tipHeight());
                    observedAnchor.set(context.anchoredHeight());
                } catch (Throwable t) {
                    contextFailure.set(t);
                } finally {
                    executionFinished.countDown();
                }
                return EffectExecution.confirmed(new byte[0]);
            }

            @Override
            public void close() {
                callActiveWhenClosed.set(executionFinished.getCount() != 0);
                executorClosed.countDown();
            }
        };

        Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS);
        EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                runtimeSettings(3), List.of(executor), Map.of(), log());
        boolean ledgerClosed = false;
        try {
            pipeline.applyNext(1);
            pipeline.store.metaPutLong("anchor_last_height", 1);
            runtime.tick();
            assertThat(executionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            runtime.close(25, 25);
            assertThat(executorClosed.getCount()).isEqualTo(1);
            CompletableFuture<Void> closeCompletion =
                    runtime.closeCompletion().toCompletableFuture();
            assertThat(closeCompletion.isDone()).isFalse();

            // The subsystem owns the store and closes it immediately after
            // runtime.close(); the still-live plugin may safely consult only
            // the immutable context snapshot from this point onward.
            pipeline.close();
            ledgerClosed = true;
            releaseExecution.countDown();

            assertThat(executionFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executorClosed.await(5, TimeUnit.SECONDS)).isTrue();
            closeCompletion.get(5, TimeUnit.SECONDS);
            assertThat(contextFailure.get()).isNull();
            assertThat(observedTip).hasValue(1);
            assertThat(observedAnchor).hasValue(1);
            assertThat(callActiveWhenClosed).isFalse();
        } finally {
            releaseExecution.countDown();
            runtime.close(0, 0);
            if (!ledgerClosed) {
                pipeline.close();
            }
        }
    }

    @Test
    void executionContext_snapshotsConfigAndDefensivelyCopiesSubmittedRef(@TempDir Path dir)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger idCalls = new AtomicInteger();
        AtomicReference<Map<String, String>> observedSettings = new AtomicReference<>();
        AtomicReference<byte[]> observedSubmittedRef = new AtomicReference<>();
        AtomicBoolean settingsImmutable = new AtomicBoolean();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override
            public String id() {
                if (idCalls.incrementAndGet() != 1) {
                    throw new AssertionError("executor id must be snapshotted once");
                }
                return "snapshot-executor";
            }
            @Override public boolean supports(String effectType) {
                return "test.action".equals(effectType);
            }

            @Override
            public EffectExecution execute(EffectExecutionContext context, PendingEffect effect) {
                if (calls.incrementAndGet() == 1) {
                    observedSettings.set(context.settings());
                    try {
                        context.settings().put("token", "plugin-mutated");
                    } catch (UnsupportedOperationException expected) {
                        settingsImmutable.set(true);
                    }
                    return EffectExecution.submitted(new byte[] {1, 2, 3});
                }
                byte[] firstRead = context.submittedRef();
                firstRead[0] = 99;
                observedSubmittedRef.set(context.submittedRef());
                return EffectExecution.confirmed(new byte[0]);
            }
        };

        Map<String, String> mutableInner = new java.util.HashMap<>();
        mutableInner.put("token", "initial");
        Map<String, Map<String, String>> mutableOuter = new java.util.HashMap<>();
        mutableOuter.put("snapshot-executor", mutableInner);

        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), mutableOuter, log())) {
            // Both input map levels may be reused/mutated by bootstrap code;
            // contexts must retain the construction-time snapshot.
            mutableInner.put("token", "changed-after-construction");
            mutableOuter.clear();

            pipeline.applyNext(1);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.SUBMITTED);
            Thread.sleep(10);
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);

            assertThat(observedSettings.get()).containsExactly(Map.entry("token", "initial"));
            assertThat(settingsImmutable).isTrue();
            assertThat(observedSubmittedRef.get()).containsExactly(1, 2, 3);
            assertThat(idCalls).hasValue(1);
        }
    }

    @Test
    void repeatedIntake_preservesTerminalStatusAndRestoresExecutableQueue(@TempDir Path dir) {
        try (Pipeline pipeline = new Pipeline(dir,
                emitting("test.action", ResultPolicy.NONE, null), FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(), Map.of(), log())) {
            pipeline.applyNext(2);
            FxStatusRecord terminal = FxStatusRecord.pending().parked("operator parked");
            FxStatusRecord retry = FxStatusRecord.pending().retry("transient", 0);
            pipeline.store.fxRuntimePutStatus(1, 0, terminal);
            pipeline.store.fxRuntimePutStatus(1, 1, retry);
            pipeline.store.fxQueueDelete(1, 0);
            pipeline.store.fxQueueDelete(1, 1);

            runtime.tick();

            assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().status())
                    .isEqualTo(FxStatusRecord.PARKED);
            assertThat(pipeline.store.fxQueueExists(1, 0)).isFalse();
            assertThat(pipeline.store.fxRuntimeStatus(1, 1).orElseThrow().encode())
                    .isEqualTo(retry.encode());
            assertThat(pipeline.store.fxQueueExists(1, 1)).isTrue();
            assertThat(pipeline.store.fxIntakeCursor(-1)).isEqualTo(1);
        }
    }

    @Test
    void executorTypePartition_appliesToQuarantineRequeueDispatchAndClaim(@TempDir Path dir)
            throws Exception {
        RecordingExecutor foreignExecutor = new RecordingExecutor("foreign.action",
                effect -> EffectExecution.confirmed(new byte[0]));
        EffectRuntime.Settings ownedOnly = new EffectRuntime.Settings(true,
                Set.of("owned.action"), 50, 1, 3, 1, 5, 0, 100);
        try (Pipeline pipeline = new Pipeline(dir, emittingTypes("owned.action", "foreign.action"),
                FX_SETTINGS)) {
            pipeline.applyNext(2); // history before this partition is enabled
            try (EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain", ownedOnly,
                    List.of(foreignExecutor), Map.of(), log())) {
                assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().status())
                        .isEqualTo(FxStatusRecord.QUARANTINED);
                assertThat(pipeline.store.fxRuntimeStatus(1, 1)).isEmpty();
                assertThat(runtime.requeue(1, 1)).isFalse();

                // Even a legacy/corrupt foreign queue row cannot escape via
                // an empty claim filter ("all") or a matching executor.
                pipeline.store.fxRuntimePutStatus(1, 1, FxStatusRecord.pending());
                pipeline.store.fxQueuePut(1, 1);
                assertThat(runtime.claim("worker", Set.of(), 10, 60)).isEmpty();
                runtime.tick();
                awaitInFlight(runtime, 0);

                assertThat(foreignExecutor.invocations).isEmpty();
                assertThat(pipeline.store.fxQueueExists(1, 1)).isFalse();
                assertThat(runtime.requeue(1, 1)).isFalse();

                // The same strict partition still permits its owned,
                // deliberately quarantined history to be reviewed/requeued.
                assertThat(runtime.requeue(1, 0)).isTrue();
                assertThat(pipeline.store.fxQueueExists(1, 0)).isTrue();
            }
        }
    }

    @Test
    void foreignOwner_clearsRuntimeAndQuarantinesOpenEffects(@TempDir Path dir) throws Exception {
        AtomicInteger sourceCalls = new AtomicInteger();
        RecordingExecutor sourceExecutor = new RecordingExecutor("test.action", effect -> {
            sourceCalls.incrementAndGet();
            return EffectExecution.submitted("source-tx".getBytes(StandardCharsets.UTF_8));
        });
        RecordingExecutor targetExecutor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed("target-tx".getBytes(StandardCharsets.UTF_8)));

        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS)) {
            try (EffectRuntime sourceRuntime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(sourceExecutor), Map.of(), "owner-a:types=", log())) {
                pipeline.applyNext(1);
                sourceRuntime.tick();
                awaitStatus(pipeline.store, 1, 0, FxStatusRecord.SUBMITTED);
            }

            byte[] stateRoot = pipeline.store.stateRoot().clone();
            byte[] blockRoot = pipeline.store.block(1).orElseThrow().stateRoot().clone();
            byte[] record = pipeline.store.fxRecord(1, 0).orElseThrow().encode();
            long openCount = pipeline.store.fxOpenCount();

            try (EffectRuntime targetRuntime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(targetExecutor), Map.of(), "owner-b:types=", log())) {
                assertThat(pipeline.store.fxRuntimeOwner()).isEqualTo("v1:owner-b:types=");
                assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().status())
                        .isEqualTo(FxStatusRecord.QUARANTINED);
                assertThat(pipeline.store.fxQueueScan(10)).isEmpty();
                assertThat(pipeline.store.fxIntakeCursor(-1)).isEqualTo(1);

                targetRuntime.tick();
                Thread.sleep(50);
                assertThat(targetExecutor.invocations).isEmpty();

                // Consensus-derived records and roots survive the disposable reset.
                assertThat(pipeline.store.stateRoot()).isEqualTo(stateRoot);
                assertThat(pipeline.store.block(1).orElseThrow().stateRoot()).isEqualTo(blockRoot);
                assertThat(pipeline.store.fxRecord(1, 0).orElseThrow().encode()).isEqualTo(record);
                assertThat(pipeline.store.fxOpenCount()).isEqualTo(openCount);

                assertThat(targetRuntime.requeue(1, 0)).isTrue();
                targetRuntime.tick();
                awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);

                pipeline.applyNext(1);
                targetRuntime.tick();
                awaitStatus(pipeline.store, 2, 0, FxStatusRecord.DONE);
                assertThat(targetExecutor.invocations).hasSize(2);
            }
        }
        assertThat(sourceCalls).hasValue(1);
    }

    @Test
    void sameOwner_preservesWarmRestartState(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> calls.incrementAndGet() == 1
                        ? EffectExecution.submitted("tx-warm".getBytes(StandardCharsets.UTF_8))
                        : EffectExecution.confirmed("tx-warm".getBytes(StandardCharsets.UTF_8)));
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS)) {
            try (EffectRuntime first = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(executor), Map.of(), "owner-a:types=", log())) {
                pipeline.applyNext(1);
                first.tick();
                awaitStatus(pipeline.store, 1, 0, FxStatusRecord.SUBMITTED);
            }

            try (EffectRuntime restarted = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(executor), Map.of(), "owner-a:types=", log())) {
                FxStatusRecord submitted = pipeline.store.fxRuntimeStatus(1, 0).orElseThrow();
                assertThat(submitted.status()).isEqualTo(FxStatusRecord.SUBMITTED);
                assertThat(submitted.submittedRef())
                        .isEqualTo("tx-warm".getBytes(StandardCharsets.UTF_8));

                Thread.sleep(5);
                restarted.tick();
                awaitStatus(pipeline.store, 1, 0, FxStatusRecord.DONE);
            }
        }
        assertThat(calls).hasValue(2);
    }

    @Test
    void unownedLegacyRuntime_resetsConservatively(@TempDir Path dir) {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.confirmed(new byte[0]));
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS)) {
            pipeline.applyNext(1);
            pipeline.store.fxPutIntakeCursor(1);
            pipeline.store.fxRuntimePutStatus(1, 0, FxStatusRecord.pending()
                    .submitted("legacy-tx".getBytes(StandardCharsets.UTF_8), 0));
            pipeline.store.fxQueuePut(1, 0);

            try (EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                    runtimeSettings(3), List.of(executor), Map.of(), "owner-a:types=", log())) {
                assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().status())
                        .isEqualTo(FxStatusRecord.QUARANTINED);
                assertThat(pipeline.store.fxQueueScan(10)).isEmpty();
                assertThat(pipeline.store.fxIntakeCursor(-1)).isEqualTo(1);
                assertThat(executor.invocations).isEmpty();
            }
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
    void retention_neverPrunesLiveObligationsOrFutureExpiry(@TempDir Path dir) throws Exception {
        RecordingExecutor executor = new RecordingExecutor("test.action",
                effect -> EffectExecution.failed("target down", false)); // parks immediately
        try (Pipeline pipeline = new Pipeline(dir, emitting("test.action", ResultPolicy.NONE, null),
                FX_SETTINGS);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     runtimeSettings(3), List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.applyNext(1); // height 1 → PARKED (an owed obligation)
            runtime.tick();
            awaitStatus(pipeline.store, 1, 0, FxStatusRecord.PARKED);

            assertThat(pipeline.store.fxPruneBelow(100)).isZero(); // PARKED never prunes
            assertThat(pipeline.store.fxRecord(1, 0)).isPresent();
        }

        // A locally-DONE CHAIN effect whose expiry bucket is still in the future
        // must survive pruning — the deterministic sweep will consume the bucket
        try (Pipeline pipeline = new Pipeline(dir.resolve("future-expiry"),
                emittingWithExpiry("test.action", 1000), FX_SETTINGS)) {
            pipeline.applyNext(1); // expiry registered at height 1001
            pipeline.store.fxRuntimePutStatus(1, 0,
                    FxStatusRecord.pending().done("ref".getBytes(StandardCharsets.UTF_8)));
            assertThat(pipeline.store.fxPruneBelow(100)).isZero(); // expiry 1001 > tip 1
            assertThat(pipeline.store.fxRecord(1, 0)).isPresent();
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

    private static AppStateMachine emittingOnlyTailAsTarget() {
        return new AppStateMachine() {
            @Override public String id() { return "tail-emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override
            public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                for (int i = 0; i < block.messages().size(); i++) {
                    AppMessage message = block.messages().get(i);
                    effects.emit(EffectIntent.of(
                            i == 4096 ? "target.action" : "unsupported.action",
                            message.getBody()).build());
                }
            }
        };
    }

    private static AppStateMachine emittingTypes(String... types) {
        return new AppStateMachine() {
            @Override public String id() { return "typed-emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override
            public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                for (int i = 0; i < block.messages().size(); i++) {
                    effects.emit(EffectIntent.of(types[i % types.length],
                            block.messages().get(i).getBody()).build());
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

    private static org.slf4j.Logger log() {
        return LoggerFactory.getLogger("fx");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
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

    private static void awaitInFlight(EffectRuntime runtime, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Object value = runtime.stats().get("inFlight");
            if (value instanceof Number number && number.intValue() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Effect runtime never reached inFlight=" + expected
                + "; current: " + runtime.stats().get("inFlight"));
    }

    private static void awaitClosed(EffectRuntime runtime) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (runtime.isClosed()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Effect runtime close did not start");
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to " + description);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting to " + description, e);
        }
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

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
