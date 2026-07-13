package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final java.util.List<StatusSnapshot> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();

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
            io.micrometer.core.instrument.FunctionCounter
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
}
