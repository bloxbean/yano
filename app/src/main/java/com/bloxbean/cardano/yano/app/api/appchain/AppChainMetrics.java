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
 *   yano_appchain_blocks_finalized_total     counter
 *   yano_appchain_messages_finalized_total   counter
 *   yano_appchain_block_interval_seconds     timer (time between finalized blocks)
 * </pre>
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

    private final Map<String, Long> lastBlockAtMillis = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent event) {
        if (!appChainEnabled) {
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

        Gauge.builder("yano.appchain.tip.height", gateway, g -> (double) g.tipHeight())
                .tag("chain", chain)
                .description("Height of the last finalized app block")
                .register(registry);
        Gauge.builder("yano.appchain.pool.size", gateway,
                        g -> doubleFromStatus(g, "poolSize"))
                .tag("chain", chain)
                .description("Pending (unfinalized) messages in the pool")
                .register(registry);
        Gauge.builder("yano.appchain.peers.connected", gateway,
                        g -> connectedPeers(g))
                .tag("chain", chain)
                .description("Connected app-group peers")
                .register(registry);

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

    private static double doubleFromStatus(AppChainGateway gateway, String key) {
        Object value = gateway.status().get(key);
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    private static double connectedPeers(AppChainGateway gateway) {
        Object peers = gateway.status().get("peers");
        if (peers instanceof Map<?, ?> peerMap) {
            return peerMap.values().stream().filter(Boolean.TRUE::equals).count();
        }
        return 0d;
    }
}
