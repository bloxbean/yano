package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppChainMetricsTest {

    @Test
    void registersEffectGaugesCountersAndBoundedTypeTimers() {
        Map<String, Object> latencyByType = new LinkedHashMap<>();
        latencyByType.put("cardano.payment", Map.of("count", 2L, "totalMillis", 1_250L));
        latencyByType.put("webhook/post:v2", Map.of("count", 4L, "totalMillis", 800L));

        Map<String, Object> effectStats = new LinkedHashMap<>();
        effectStats.put("openOnChain", 7L);
        effectStats.put("queueDepth", 5L);
        effectStats.put("inFlight", 2L);
        effectStats.put("statusCounts", Map.of(
                "PENDING", 3L,
                "RETRY", 1L,
                "SUBMITTED", 2L,
                "EXTERNAL", 1L,
                "PARKED", 4L,
                "QUARANTINED", 6L));
        effectStats.put("resultBacklog", 2L);
        effectStats.put("resultBacklogByType", Map.of(
                "cardano.payment", 2L,
                "webhook/post:v2", 0L));
        effectStats.put("oldestPending", Map.of("ageBlocks", 19L, "ageSeconds", 42.5d));
        effectStats.put("executionTotals", Map.of(
                "confirmed", 11L,
                "failed", 3L,
                "parked", 4L));
        effectStats.put("expiredTotal", 9L);
        effectStats.put("latencyByType", latencyByType);
        effectStats.put("executorOperations", List.of(Map.ofEntries(
                Map.entry("id", "kafka-publish"),
                Map.entry("readiness", "READY"),
                Map.entry("attempts", 8L),
                Map.entry("successes", 7L),
                Map.entry("retryableFailures", 1L),
                Map.entry("terminalFailures", 0L),
                Map.entry("inFlight", 1),
                Map.entry("lastSuccessAge", "LESS_THAN_ONE_MINUTE"),
                Map.entry("lastFailureAge", "LESS_THAN_FIVE_MINUTES"))));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppChainMetrics metrics = metrics(registry, gateway("chain-a", effectStats));
        metrics.onStart(null);

        assertEquals(7d, gauge(registry, "yano.appchain.effects.open"), 0.0001d);
        assertEquals(5d, gauge(registry, "yano.appchain.effects.queue.depth"), 0.0001d);
        assertEquals(2d, gauge(registry, "yano.appchain.effects.in.flight"), 0.0001d);
        assertEquals(2d, gauge(registry, "yano.appchain.effects.result.backlog"), 0.0001d);
        assertEquals(2d, registry.get("yano.appchain.effects.result.backlog.by.type")
                .tags("chain", "chain-a", "type", "cardano.payment")
                .gauge().value(), 0.0001d);
        assertEquals(19d, gauge(registry,
                "yano.appchain.effects.oldest.pending.age.blocks"), 0.0001d);
        assertEquals(42.5d, gauge(registry,
                "yano.appchain.effects.oldest.pending.age"), 0.0001d);

        assertEquals(3d, registry.get("yano.appchain.effects.runtime.status")
                .tags("chain", "chain-a", "status", "pending").gauge().value(), 0.0001d);
        assertEquals(4d, registry.get("yano.appchain.effects.runtime.status")
                .tags("chain", "chain-a", "status", "parked").gauge().value(), 0.0001d);
        assertEquals(0d, registry.get("yano.appchain.effects.runtime.status")
                .tags("chain", "chain-a", "status", "done").gauge().value(), 0.0001d);
        assertEquals(8, registry.find("yano.appchain.effects.runtime.status")
                .tag("chain", "chain-a").gauges().size());

        assertEquals(11d, registry.get("yano.appchain.effects.execution")
                .tags("chain", "chain-a", "outcome", "confirmed")
                .functionCounter().count(), 0.0001d);
        assertEquals(3d, registry.get("yano.appchain.effects.execution")
                .tags("chain", "chain-a", "outcome", "failed")
                .functionCounter().count(), 0.0001d);
        assertEquals(9d, registry.get("yano.appchain.effects.expired")
                .tag("chain", "chain-a").functionCounter().count(), 0.0001d);

        FunctionTimer paymentLatency = registry.get("yano.appchain.effects.execution.latency")
                .tags("chain", "chain-a", "type", "cardano.payment").functionTimer();
        assertEquals(2d, paymentLatency.count(), 0.0001d);
        assertEquals(1_250d, paymentLatency.totalTime(TimeUnit.MILLISECONDS), 0.0001d);
        FunctionTimer webhookLatency = registry.get("yano.appchain.effects.execution.latency")
                .tags("chain", "chain-a", "type", "webhook/post:v2").functionTimer();
        assertEquals(4d, webhookLatency.count(), 0.0001d);
        assertEquals(800d, webhookLatency.totalTime(TimeUnit.MILLISECONDS), 0.0001d);

        assertEquals(1d, registry.get("yano.appchain.effects.executor.readiness")
                .tags("chain", "chain-a", "executor", "kafka-publish",
                        "slot", "0", "state", "ready").gauge().value(), 0.0001d);
        assertEquals(0d, registry.get("yano.appchain.effects.executor.readiness")
                .tags("chain", "chain-a", "executor", "kafka-publish",
                        "slot", "0", "state", "degraded").gauge().value(), 0.0001d);
        assertEquals(1d, registry.get("yano.appchain.effects.executor.in.flight")
                .tags("chain", "chain-a", "executor", "kafka-publish", "slot", "0")
                .gauge().value(), 0.0001d);
        assertEquals(8d, registry.get("yano.appchain.effects.executor.attempts")
                .tags("chain", "chain-a", "executor", "kafka-publish", "slot", "0")
                .functionCounter().count(), 0.0001d);
        assertEquals(60d, registry.get("yano.appchain.effects.executor.age.bucket")
                .tags("chain", "chain-a", "executor", "kafka-publish",
                        "slot", "0", "event", "success").gauge().value(), 0.0001d);

        // Meter cardinality is fixed at startup. A later stats key is readable
        // data, but it cannot create a new Micrometer series dynamically.
        latencyByType.put("late.dynamic", Map.of("count", 1L, "totalMillis", 5L));
        assertNull(registry.find("yano.appchain.effects.execution.latency")
                .tags("chain", "chain-a", "type", "late.dynamic").functionTimer());
        assertEquals(2, registry.find("yano.appchain.effects.execution.latency")
                .tag("chain", "chain-a").functionTimers().size());
        metrics.onStop(null);
    }

    @Test
    void executorMetersFreezeWhenRuntimeInventoryAppearsAfterStartup() throws Exception {
        AtomicReference<Map<String, Object>> effectStats = new AtomicReference<>(Map.of());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppChainMetrics metrics = metrics(registry, gateway("chain-late", effectStats::get));
        metrics.onStart(null);

        assertTrue(registry.find("yano.appchain.effects.executor.readiness")
                .tag("chain", "chain-late").gauges().isEmpty());

        effectStats.set(Map.of("executorOperations", List.of(Map.ofEntries(
                Map.entry("id", "kafka-publish"),
                Map.entry("readiness", "READY"),
                Map.entry("attempts", 1L),
                Map.entry("successes", 1L),
                Map.entry("retryableFailures", 0L),
                Map.entry("terminalFailures", 0L),
                Map.entry("inFlight", 0),
                Map.entry("lastSuccessAge", "LESS_THAN_ONE_MINUTE"),
                Map.entry("lastFailureAge", "NEVER")))));
        Thread.sleep(1_050);
        metrics.refreshExecutorMeters();

        assertEquals(4, registry.find("yano.appchain.effects.executor.readiness")
                .tags("chain", "chain-late", "executor", "kafka-publish")
                .gauges().size());

        effectStats.set(Map.of("executorOperations", List.of(
                Map.of("id", "kafka-publish"), Map.of("id", "late-dynamic"))));
        metrics.refreshExecutorMeters();
        assertTrue(registry.find("yano.appchain.effects.executor.readiness")
                .tags("chain", "chain-late", "executor", "late-dynamic")
                .gauges().isEmpty());
        metrics.onStop(null);
    }

    @Test
    void missingEffectRuntimeValuesRemainZeroWithoutCreatingTypeMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppChainMetrics metrics = metrics(registry, gateway("chain-empty", Map.of(
                "openOnChain", 0L,
                "latencyByType", Map.of())));
        metrics.onStart(null);

        assertEquals(0d, registry.get("yano.appchain.effects.open")
                .tag("chain", "chain-empty").gauge().value(), 0.0001d);
        assertEquals(0d, registry.get("yano.appchain.effects.queue.depth")
                .tag("chain", "chain-empty").gauge().value(), 0.0001d);
        assertEquals(0d, registry.get("yano.appchain.effects.runtime.status")
                .tags("chain", "chain-empty", "status", "quarantined")
                .gauge().value(), 0.0001d);
        assertTrue(registry.find("yano.appchain.effects.execution.latency")
                .tag("chain", "chain-empty").functionTimers().isEmpty());
        metrics.onStop(null);
    }

    @Test
    void registersBoundedCompositeGovernanceGaugesFromStatusSnapshot() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppChainGateway gateway = gateway("chain-governed", Map.of());
        AppChainGateway governed = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "chainId" -> "chain-governed";
                    case "tipHeight" -> 8L;
                    case "status" -> Map.of(
                            "poolSize", 0,
                            "peers", Map.of(),
                            "drops", Map.of(),
                            "stalled", false,
                            "stateMachineStatus", Map.of(
                                    "currentEpoch", 2L,
                                    "proposalStatus", "SCHEDULED",
                                    "approvals", 2,
                                    "readiness", 3,
                                    "locallyReady", true,
                                    "retiredDrains", 1));
                    case "effectStats" -> Map.of();
                    case "subscribeFinalized" -> (AutoCloseable) () -> { };
                    case "toString" -> gateway.toString();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        AppChainMetrics metrics = metrics(registry, governed);
        metrics.onStart(null);

        assertEquals(2d, registry.get("yano.appchain.composite.profile.epoch")
                .tag("chain", "chain-governed").gauge().value(), 0.0001d);
        assertEquals(3d, registry.get("yano.appchain.composite.governance.proposal.state")
                .tag("chain", "chain-governed").gauge().value(), 0.0001d);
        assertEquals(2d, registry.get("yano.appchain.composite.governance.approvals")
                .tag("chain", "chain-governed").gauge().value(), 0.0001d);
        assertEquals(3d, registry.get("yano.appchain.composite.governance.readiness")
                .tag("chain", "chain-governed").gauge().value(), 0.0001d);
        assertEquals(1d, registry.get("yano.appchain.composite.governance.local.ready")
                .tag("chain", "chain-governed").gauge().value(), 0.0001d);
        assertEquals(1d, registry.get("yano.appchain.composite.governance.retired.drains")
                .tag("chain", "chain-governed").gauge().value(), 0.0001d);
        metrics.onStop(null);
    }

    @Test
    void cumulativeMetersSurviveTransientFailuresAndRuntimeCounterResets() throws Exception {
        Map<String, Object> initial = Map.of(
                "metricsGeneration", "generation-a",
                "executionTotals", Map.of("confirmed", 11L, "failed", 3L, "parked", 4L),
                "expiredTotal", 9L,
                "latencyByType", Map.of("all", Map.of(
                        "count", 2L, "totalMillis", 1_250L)));
        AtomicReference<Map<String, Object>> stats = new AtomicReference<>(initial);
        AtomicBoolean fail = new AtomicBoolean();
        AppChainGateway gateway = gateway("chain-reset", () -> {
            if (fail.get()) {
                throw new IllegalStateException("temporary stats failure");
            }
            return stats.get();
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppChainMetrics metrics = metrics(registry, gateway);
        metrics.onStart(null);

        var confirmed = registry.get("yano.appchain.effects.execution")
                .tags("chain", "chain-reset", "outcome", "confirmed").functionCounter();
        var expired = registry.get("yano.appchain.effects.expired")
                .tag("chain", "chain-reset").functionCounter();
        var latency = registry.get("yano.appchain.effects.execution.latency")
                .tags("chain", "chain-reset", "type", "all").functionTimer();
        assertEquals(11d, confirmed.count(), 0.0001d);
        assertEquals(9d, expired.count(), 0.0001d);
        assertEquals(2d, latency.count(), 0.0001d);

        fail.set(true);
        Thread.sleep(1_050);
        assertEquals(11d, confirmed.count(), 0.0001d);
        assertEquals(9d, expired.count(), 0.0001d);
        assertEquals(1_250d, latency.totalTime(TimeUnit.MILLISECONDS), 0.0001d);

        // AppChainSubsystem can briefly expose this sparse snapshot while an
        // EffectRuntime is closing. Missing cumulative fields must not be
        // interpreted as a reset to zero.
        fail.set(false);
        stats.set(Map.of("closed", true));
        Thread.sleep(1_050);
        assertEquals(11d, confirmed.count(), 0.0001d);
        assertEquals(9d, expired.count(), 0.0001d);
        assertEquals(2d, latency.count(), 0.0001d);
        stats.set(Map.of(
                "metricsGeneration", "generation-b",
                "executionTotals", Map.of("confirmed", 2L, "failed", 3L, "parked", 4L),
                "expiredTotal", 1L,
                "latencyByType", Map.of("all", Map.of(
                        "count", 1L, "totalMillis", 100L))));
        Thread.sleep(1_050);
        assertEquals(13d, confirmed.count(), 0.0001d);
        assertEquals(10d, expired.count(), 0.0001d);
        assertEquals(3d, latency.count(), 0.0001d);
        assertEquals(1_350d, latency.totalTime(TimeUnit.MILLISECONDS), 0.0001d);
        metrics.onStop(null);
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        return registry.get(name).tag("chain", "chain-a").gauge().value();
    }

    private static AppChainMetrics metrics(SimpleMeterRegistry registry, AppChainGateway gateway) {
        AppChainMetrics metrics = new AppChainMetrics();
        metrics.registry = registry;
        metrics.appChainGateways = new AppChainGateways() {
            @Override
            public Optional<AppChainGateway> byId(String chainId) {
                return gateway.chainId().equals(chainId) ? Optional.of(gateway) : Optional.empty();
            }

            @Override
            public Collection<AppChainGateway> all() {
                return List.of(gateway);
            }
        };
        metrics.appChainEnabled = true;
        metrics.firstChainId = Optional.empty();
        return metrics;
    }

    private static AppChainGateway gateway(String chainId, Map<String, Object> effectStats) {
        return gateway(chainId, () -> effectStats);
    }

    private static AppChainGateway gateway(String chainId,
                                           Supplier<Map<String, Object>> effectStats) {
        return (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "chainId" -> chainId;
                    case "tipHeight" -> 0L;
                    case "status" -> Map.of(
                            "poolSize", 0,
                            "peers", Map.of(),
                            "drops", Map.of(),
                            "stalled", false);
                    case "effectStats" -> effectStats.get();
                    case "subscribeFinalized" -> (AutoCloseable) () -> { };
                    case "toString" -> "TestAppChainGateway[" + chainId + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == byte[].class) return new byte[0];
        if (Optional.class.isAssignableFrom(type)) return Optional.empty();
        if (List.class.isAssignableFrom(type)) return List.of();
        if (Set.class.isAssignableFrom(type)) return Set.of();
        if (Map.class.isAssignableFrom(type)) return Map.of();
        return null;
    }
}
