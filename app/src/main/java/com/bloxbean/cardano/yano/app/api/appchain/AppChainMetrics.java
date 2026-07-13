package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * App-chain Micrometer metrics (ADR app-layer/006 E5.1), exposed with the
 * standard Quarkus Prometheus endpoint ({@code /q/metrics}). Registered only
 * when the app chain is enabled; per-chain tag {@code chain}.
 *
 * <pre>
 *   yano_appchain_tip_height{chain}          gauge
 *   yano_appchain_pool_size{chain}           gauge
 *   yano_appchain_peers_connected{chain}     gauge
 *   yano_appchain_stalled{chain}             gauge (0/1 — peer ahead, no progress)
 *   yano_appchain_anchor_lag_blocks{chain}   gauge (tip − last anchored height)
 *   yano_appchain_sink_lag_blocks{chain,sink} gauge (tip − sink cursor)
 *   yano_appchain_messages_dropped_total{chain,reason} counter (pool_full, stale_seq, ...)
 *   yano_appchain_blocks_finalized_total     counter
 *   yano_appchain_messages_finalized_total   counter
 *   yano_appchain_block_interval_seconds     timer (time between finalized blocks)
 *   yano_appchain_effects_open                gauge
 *   yano_appchain_effects_queue_depth         gauge
 *   yano_appchain_effects_runtime_status      gauge (tag: status)
 *   yano_appchain_effects_result_backlog      gauge
 *   yano_appchain_effects_execution_total     function counter (tag: outcome)
 *   yano_appchain_effects_expired_total       function counter
 *   yano_appchain_effects_execution_latency_seconds function timer (tag: type)
 * </pre>
 *
 * Status-backed gauges read ONE memoized {@code status()} snapshot (~1s TTL)
 * per scrape instead of rebuilding it per gauge (008.1 I1.8).
 */
@ApplicationScoped
public class AppChainMetrics {

    private static final Logger log = Logger.getLogger(AppChainMetrics.class);

    @Inject
    MeterRegistry registry;

    @Inject
    AppChainGateways appChainGateways;

    @ConfigProperty(name = YanoPropertyKeys.AppChain.ENABLED, defaultValue = "false")
    boolean appChainEnabled;

    // Multi-chain config (chains[0].chain-id) auto-enables the app chain without
    // the flat enabled flag — the metrics gate must see that too.
    @ConfigProperty(name = "yano.app-chain.chains[0].chain-id")
    java.util.Optional<String> firstChainId;

    private final Map<String, Long> lastBlockAtMillis = new ConcurrentHashMap<>();

    // Micrometer holds gauge/function-counter state WEAKLY — without a strong
    // reference the snapshot is GC'd and every gauge reads NaN (found by the
    // devnet regression run, 008.1 I1.8)
    private final List<StatusSnapshot> snapshots = new CopyOnWriteArrayList<>();
    private final List<EffectStatsSnapshot> effectSnapshots = new CopyOnWriteArrayList<>();

    /** Fixed runtime states: tag cardinality cannot grow with workload data. */
    private static final List<String> EFFECT_STATUSES = List.of(
            "pending", "retry", "submitted", "external",
            "done", "parked", "quarantined", "skipped");
    /** Fixed terminal outcomes exposed by the runtime's monotonic counters. */
    private static final List<String> EFFECT_OUTCOMES = List.of(
            "confirmed", "failed", "parked");

    void onStart(@Observes StartupEvent event) {
        if (!appChainEnabled && firstChainId.isEmpty()) {
            return;
        }
        try {
            for (AppChainGateway gateway : appChainGateways.all()) {
                register(gateway);
            }
        } catch (Exception e) {
            log.warnf("App-chain metrics registration failed: %s", e.toString());
        }
    }

    private void register(AppChainGateway gateway) {
        String chain = gateway.chainId();
        StatusSnapshot snapshot = new StatusSnapshot(gateway);
        snapshots.add(snapshot); // strong ref — see field comment
        EffectStatsSnapshot effectSnapshot = new EffectStatsSnapshot(gateway);
        effectSnapshots.add(effectSnapshot); // Micrometer also holds these weakly

        Gauge.builder("yano.appchain.tip.height", gateway, g -> (double) g.tipHeight())
                .tag("chain", chain)
                .description("Height of the last finalized app block")
                .register(registry);
        Gauge.builder("yano.appchain.pool.size", snapshot,
                        s -> s.number("poolSize"))
                .tag("chain", chain)
                .description("Pending (unfinalized) messages in the pool")
                .register(registry);
        Gauge.builder("yano.appchain.peers.connected", snapshot,
                        StatusSnapshot::connectedPeers)
                .tag("chain", chain)
                .description("Connected app-group peers")
                .register(registry);
        Gauge.builder("yano.appchain.stalled", snapshot,
                        s -> Boolean.TRUE.equals(s.get().get("stalled")) ? 1d : 0d)
                .tag("chain", chain)
                .description("1 when a peer is ahead with no local progress")
                .register(registry);
        Gauge.builder("yano.appchain.anchor.lag.blocks", snapshot,
                        s -> s.nested("anchor", "lagBlocks"))
                .tag("chain", chain)
                .description("Finalized blocks not yet covered by a confirmed L1 anchor")
                .register(registry);
        for (String reason : new String[]{"pool_full", "stale_seq", "bad_auth", "not_member"}) {
            FunctionCounter
                    .builder("yano.appchain.messages.dropped", snapshot,
                            s -> s.drop(reason))
                    .tag("chain", chain)
                    .tag("reason", reason)
                    .description("Messages dropped before sequencing, by reason")
                    .register(registry);
        }
        // Per-sink lag gauges for sinks known at startup (config-defined)
        Object sinks = snapshot.get().get("sinks");
        if (sinks instanceof Map<?, ?> sinkMap) {
            for (Object sinkId : sinkMap.keySet()) {
                String id = String.valueOf(sinkId);
                Gauge.builder("yano.appchain.sink.lag.blocks", snapshot,
                                s -> s.sinkLag(id))
                        .tag("chain", chain)
                        .tag("sink", id)
                        .description("Finalized blocks not yet delivered to the sink")
                        .register(registry);
            }
        }

        registerEffectMetrics(chain, effectSnapshot);

        Counter blocks = Counter.builder("yano.appchain.blocks.finalized")
                .tag("chain", chain)
                .description("Finalized app blocks")
                .register(registry);
        Counter messages = Counter.builder("yano.appchain.messages.finalized")
                .tag("chain", chain)
                .description("Messages finalized into app blocks")
                .register(registry);
        Timer blockInterval = Timer.builder("yano.appchain.block.interval")
                .tag("chain", chain)
                .description("Time between consecutive finalized blocks")
                .register(registry);

        gateway.subscribeFinalized((block, hash) -> {
            blocks.increment();
            messages.increment(block.messages().size());
            long now = System.currentTimeMillis();
            Long previous = lastBlockAtMillis.put(chain, now);
            if (previous != null) {
                blockInterval.record(java.time.Duration.ofMillis(now - previous));
            }
        });
        log.infof("App-chain metrics registered for chain '%s'", chain);
    }

    /**
     * ADR-010 F12 effect observability. Current-state values are gauges;
     * cumulative transition values are function counters/timers. Runtime status
     * and outcome tags come from fixed lists. Effect-type timers are registered
     * only for the bounded keys present in the first stats snapshot, so a later
     * workload value can never create an unbounded meter series.
     */
    private void registerEffectMetrics(String chain, EffectStatsSnapshot snapshot) {
        Gauge.builder("yano.appchain.effects.open", snapshot,
                        s -> s.number("openOnChain"))
                .tag("chain", chain)
                .description("Open on-chain CHAIN effects")
                .register(registry);
        Gauge.builder("yano.appchain.effects.queue.depth", snapshot,
                        s -> s.number("queueDepth"))
                .tag("chain", chain)
                .description("Effects in this node's execution queue")
                .register(registry);
        Gauge.builder("yano.appchain.effects.in.flight", snapshot,
                        s -> s.number("inFlight"))
                .tag("chain", chain)
                .description("Effects currently executing on this node")
                .register(registry);
        for (String status : EFFECT_STATUSES) {
            Gauge.builder("yano.appchain.effects.runtime.status", snapshot,
                            s -> s.nestedNumber("statusCounts", status))
                    .tag("chain", chain)
                    .tag("status", status)
                    .description("Current effects tracked in a runtime state")
                    .register(registry);
        }
        Gauge.builder("yano.appchain.effects.result.backlog", snapshot,
                        s -> s.number("resultBacklog"))
                .tag("chain", chain)
                .description("Locally terminal CHAIN outcomes awaiting on-chain closure")
                .register(registry);
        for (String type : snapshot.backlogTypes()) {
            Gauge.builder("yano.appchain.effects.result.backlog.by.type", snapshot,
                            s -> s.mapNumber("resultBacklogByType", type))
                    .tag("chain", chain)
                    .tag("type", type)
                    .description("Result backlog by configured bounded effect-type bucket")
                    .register(registry);
        }
        Gauge.builder("yano.appchain.effects.oldest.pending.age.blocks", snapshot,
                        s -> s.nestedNumber("oldestPending", "ageBlocks"))
                .tag("chain", chain)
                .baseUnit("blocks")
                .description("Block age of the oldest active execution-queue entry")
                .register(registry);
        Gauge.builder("yano.appchain.effects.oldest.pending.age", snapshot,
                        s -> s.nestedNumber("oldestPending", "ageSeconds"))
                .tag("chain", chain)
                .baseUnit("seconds")
                .description("Wall-clock age in seconds of the oldest active execution-queue entry")
                .register(registry);

        for (String outcome : EFFECT_OUTCOMES) {
            FunctionCounter.builder("yano.appchain.effects.execution", snapshot,
                            s -> s.monotonicNestedNumber("executionTotals", outcome))
                    .tag("chain", chain)
                    .tag("outcome", outcome)
                    .description("Terminal effect executions on this runtime, by outcome")
                    .register(registry);
        }
        FunctionCounter.builder("yano.appchain.effects.expired", snapshot,
                        s -> s.monotonicNumber("expiredTotal"))
                .tag("chain", chain)
                .description("Effects deterministically expired in committed app blocks")
                .register(registry);

        for (String type : snapshot.latencyTypes()) {
            FunctionTimer.builder("yano.appchain.effects.execution.latency", snapshot,
                            s -> (long) s.monotonicLatencyNumber(type, "count"),
                            s -> s.monotonicLatencyNumber(type, "totalMillis"),
                            TimeUnit.MILLISECONDS)
                    .tag("chain", chain)
                    .tag("type", type)
                    .description("Emission-to-local-terminal effect latency by bounded type bucket")
                    .register(registry);
        }
    }

    /** Memoized status() snapshot: one gateway call feeds all gauges per scrape. */
    private static final class StatusSnapshot {
        private static final long TTL_MS = 1_000;
        private final AppChainGateway gateway;
        private volatile Map<String, Object> cached = Map.of();
        private volatile long cachedAt;

        StatusSnapshot(AppChainGateway gateway) {
            this.gateway = gateway;
        }

        Map<String, Object> get() {
            long now = System.currentTimeMillis();
            if (now - cachedAt > TTL_MS) {
                try {
                    cached = gateway.status();
                } catch (Exception e) {
                    cached = Map.of();
                }
                cachedAt = now;
            }
            return cached;
        }

        double number(String key) {
            Object value = get().get(key);
            return value instanceof Number n ? n.doubleValue() : 0d;
        }

        double nested(String outer, String key) {
            Object map = get().get(outer);
            if (map instanceof Map<?, ?> m && m.get(key) instanceof Number n) {
                return n.doubleValue();
            }
            return 0d;
        }

        double drop(String reason) {
            Object drops = get().get("drops");
            if (drops instanceof Map<?, ?> m && m.get(reason) instanceof Number n) {
                return n.doubleValue();
            }
            return 0d;
        }

        double sinkLag(String sinkId) {
            Object sinks = get().get("sinks");
            if (sinks instanceof Map<?, ?> m && m.get(sinkId) instanceof Map<?, ?> sink
                    && sink.get("lagBlocks") instanceof Number n) {
                return n.doubleValue();
            }
            return 0d;
        }

        double connectedPeers() {
            Object peers = get().get("peers");
            if (peers instanceof Map<?, ?> peerMap) {
                return peerMap.values().stream().filter(Boolean.TRUE::equals).count();
            }
            return 0d;
        }
    }

    /** Memoized effectStats() snapshot: one gateway call feeds every effect meter per scrape. */
    private static final class EffectStatsSnapshot {
        private static final long TTL_MS = 1_000;
        private final AppChainGateway gateway;
        private final Map<String, MonotonicReading> monotonicReadings = new ConcurrentHashMap<>();
        private volatile Map<String, Object> cached = Map.of();
        private volatile long cachedAt;

        EffectStatsSnapshot(AppChainGateway gateway) {
            this.gateway = gateway;
        }

        Map<String, Object> get() {
            long now = System.currentTimeMillis();
            if (now - cachedAt > TTL_MS) {
                try {
                    Map<String, Object> current = gateway.effectStats();
                    if (current != null) {
                        cached = current;
                    }
                } catch (Exception e) {
                    // Preserve the last good cumulative values. Publishing a
                    // transient zero makes Micrometer function counters and
                    // timers decrease, which produces false rate spikes.
                }
                cachedAt = now;
            }
            return cached;
        }

        double number(String key) {
            return nonNegative(get().get(key));
        }

        double monotonicNumber(String key) {
            return monotonic(key, nonNegativeOrNull(get().get(key)), false);
        }

        double nestedNumber(String outer, String key) {
            Object nested = get().get(outer);
            if (!(nested instanceof Map<?, ?> map)) {
                return 0d;
            }
            return nonNegative(valueIgnoreCase(map, key));
        }

        double monotonicNestedNumber(String outer, String key) {
            Object nested = get().get(outer);
            Object raw = nested instanceof Map<?, ?> map ? valueIgnoreCase(map, key) : null;
            return monotonic(outer + "." + key, nonNegativeOrNull(raw), true);
        }

        List<String> latencyTypes() {
            Object value = get().get("latencyByType");
            if (!(value instanceof Map<?, ?> map)) {
                return List.of();
            }
            return map.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() instanceof Map<?, ?>)
                    .map(entry -> String.valueOf(entry.getKey()))
                    .distinct()
                    .sorted()
                    .toList();
        }

        List<String> backlogTypes() {
            Object value = get().get("resultBacklogByType");
            if (!(value instanceof Map<?, ?> map)) {
                return List.of();
            }
            return map.keySet().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .distinct()
                    .sorted()
                    .toList();
        }

        double mapNumber(String outer, String key) {
            Object value = get().get(outer);
            if (!(value instanceof Map<?, ?> map)) {
                return 0d;
            }
            return nonNegative(valueIgnoreCase(map, key));
        }

        double monotonicLatencyNumber(String type, String key) {
            Object value = get().get("latencyByType");
            Object raw = null;
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (type.equals(String.valueOf(entry.getKey()))
                            && entry.getValue() instanceof Map<?, ?> latency) {
                        raw = valueIgnoreCase(latency, key);
                        break;
                    }
                }
            }
            return monotonic("latency." + type + "." + key,
                    nonNegativeOrNull(raw), true);
        }

        private double monotonic(String key, Double raw, boolean runtimeScoped) {
            String generation = runtimeScoped
                    ? String.valueOf(get().getOrDefault("metricsGeneration", "unknown"))
                    : "consensus";
            MonotonicReading reading = monotonicReadings.computeIfAbsent(
                    key, ignored -> new MonotonicReading());
            // A shutdown race can expose the runtime's intentionally sparse
            // {closed:true} snapshot. Missing cumulative fields are not zero:
            // retain the previous reading so restart cannot double the total.
            return raw != null
                    ? reading.update(raw, generation, runtimeScoped)
                    : reading.current();
        }

        private static Object valueIgnoreCase(Map<?, ?> map, String key) {
            Object direct = map.get(key);
            if (direct != null) {
                return direct;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null
                        && key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private static double nonNegative(Object value) {
            Double result = nonNegativeOrNull(value);
            return result != null ? result : 0d;
        }

        private static Double nonNegativeOrNull(Object value) {
            if (!(value instanceof Number number)) {
                return null;
            }
            double result = number.doubleValue();
            return Double.isFinite(result) ? Math.max(0d, result) : null;
        }

        /** Converts resettable runtime totals into process-lifetime monotonic values. */
        private static final class MonotonicReading {
            private boolean initialized;
            private double previousRaw;
            private double offset;
            private String previousGeneration;

            synchronized double update(double raw, String generation, boolean resetOnGeneration) {
                if (!initialized) {
                    initialized = true;
                } else if (resetOnGeneration
                        && !java.util.Objects.equals(generation, previousGeneration)) {
                    offset += previousRaw;
                } else if (raw < previousRaw) {
                    offset += previousRaw;
                }
                previousRaw = raw;
                previousGeneration = generation;
                return offset + raw;
            }

            synchronized double current() {
                return initialized ? offset + previousRaw : 0d;
            }
        }
    }
}
