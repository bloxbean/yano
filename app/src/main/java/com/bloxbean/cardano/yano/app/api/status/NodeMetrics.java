package com.bloxbean.cardano.yano.app.api.status;

import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.runtime.utxo.UtxoStatusProvider;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Bounded L1 node metrics used by Prometheus and the unified console.
 *
 * <p>All tags are fixed enums owned by Yano. Peer ids, addresses,
 * transaction ids, exception text, and other workload-controlled values are
 * deliberately excluded.</p>
 */
@ApplicationScoped
public class NodeMetrics {

    static final List<String> METRIC_NAMES = List.of(
            "yano.node.tip.local.block",
            "yano.node.tip.remote.block",
            "yano.node.sync.gap.blocks",
            "yano.node.peers.connections",
            "yano.node.peers.governor",
            "yano.node.mempool.transactions",
            "yano.node.mempool.bytes",
            "yano.node.mempool.capacity.transactions",
            "yano.node.mempool.capacity.bytes",
            "yano.node.tx.diffusion.total",
            "yano.node.tx.diffusion.inflight.transactions",
            "yano.node.tx.diffusion.inflight.bytes",
            "yano.node.utxo.enabled",
            "yano.node.utxo.last.applied.block",
            "yano.node.utxo.lag.blocks");

    private static final Logger log = Logger.getLogger(NodeMetrics.class);

    @Inject
    MeterRegistry registry;

    @Inject
    NodeLifecycle nodeLifecycle;

    @Inject
    LedgerQuery ledgerQuery;

    private volatile Snapshot snapshot;

    void onStart(@Observes StartupEvent ignored) {
        try {
            register();
        } catch (RuntimeException e) {
            log.warn("L1 node metrics registration failed");
        }
    }

    void register() {
        gauge("yano.node.tip.local.block", Snapshot::localTip,
                "Current local L1 block height");
        gauge("yano.node.tip.remote.block", Snapshot::remoteTip,
                "Latest observed upstream L1 block height");
        gauge("yano.node.sync.gap.blocks", Snapshot::syncGap,
                "Non-negative remote-to-local L1 block gap");

        taggedGauge("yano.node.peers.connections", "type", "inbound",
                Snapshot::inboundConnections, "Current relay connections by bounded type");
        taggedGauge("yano.node.peers.connections", "type", "outbound",
                Snapshot::outboundConnections, "Current relay connections by bounded type");
        taggedGauge("yano.node.peers.connections", "type", "established",
                Snapshot::establishedConnections, "Current relay connections by bounded type");

        taggedGauge("yano.node.peers.governor", "state", "cold",
                Snapshot::coldPeers, "Current peers by bounded governor state");
        taggedGauge("yano.node.peers.governor", "state", "warm",
                Snapshot::warmPeers, "Current peers by bounded governor state");
        taggedGauge("yano.node.peers.governor", "state", "hot",
                Snapshot::hotPeers, "Current peers by bounded governor state");
        taggedGauge("yano.node.peers.governor", "state", "backoff",
                Snapshot::backoffPeers, "Current peers by bounded governor state");
        taggedGauge("yano.node.peers.governor", "state", "quarantined",
                Snapshot::quarantinedPeers, "Current peers by bounded governor state");

        gauge("yano.node.mempool.transactions", Snapshot::mempoolTransactions,
                "Current mempool transaction count");
        gauge("yano.node.mempool.bytes", Snapshot::mempoolBytes,
                "Current mempool retained bytes");
        gauge("yano.node.mempool.capacity.transactions", Snapshot::mempoolMaxTransactions,
                "Configured mempool transaction capacity");
        gauge("yano.node.mempool.capacity.bytes", Snapshot::mempoolMaxBytes,
                "Configured mempool byte capacity");

        functionCounter("outbound_forwarded", Snapshot::outboundForwarded);
        functionCounter("outbound_suppressed", Snapshot::outboundSuppressed);
        functionCounter("inbound_accepted", Snapshot::inboundAccepted);
        functionCounter("inbound_rejected", Snapshot::inboundRejected);
        functionCounter("inbound_ignored", Snapshot::inboundIgnored);
        functionCounter("served", Snapshot::served);
        gauge("yano.node.tx.diffusion.inflight.transactions", Snapshot::inflightTransactions,
                "Current requested inbound transaction bodies");
        gauge("yano.node.tx.diffusion.inflight.bytes", Snapshot::inflightBytes,
                "Current requested inbound transaction bytes");

        gauge("yano.node.utxo.enabled", Snapshot::utxoEnabled,
                "1 when the UTXO store is enabled and exposes status");
        gauge("yano.node.utxo.last.applied.block", Snapshot::utxoLastApplied,
                "Last L1 block applied to the UTXO store");
        gauge("yano.node.utxo.lag.blocks", Snapshot::utxoLag,
                "Non-negative local-tip-to-UTXO-store block gap");
    }

    private void gauge(String name, ToDoubleFunction<Snapshot> value, String description) {
        Gauge.builder(name, this, metrics -> value.applyAsDouble(metrics.current()))
                .description(description)
                .register(registry);
    }

    private void taggedGauge(String name, String tagName, String tagValue,
                             ToDoubleFunction<Snapshot> value, String description) {
        Gauge.builder(name, this, metrics -> value.applyAsDouble(metrics.current()))
                .tag(tagName, tagValue)
                .description(description)
                .register(registry);
    }

    private void functionCounter(String outcome, ToDoubleFunction<Snapshot> value) {
        FunctionCounter.builder("yano.node.tx.diffusion.total", this,
                        metrics -> value.applyAsDouble(metrics.current()))
                .tag("outcome", outcome)
                .description("Process-lifetime transaction diffusion outcomes")
                .register(registry);
    }

    private Snapshot current() {
        long now = System.nanoTime();
        Snapshot cached = snapshot;
        if (cached != null && now - cached.createdAtNanos() < 250_000_000L) {
            return cached;
        }
        synchronized (this) {
            cached = snapshot;
            if (cached == null || now - cached.createdAtNanos() >= 250_000_000L) {
                snapshot = cached = capture(now);
            }
            return cached;
        }
    }

    private Snapshot capture(long now) {
        NodeStatus status = nodeLifecycle.getStatus();
        double localTip = number(status.getLocalTipBlockNumber());
        double remoteTip = status.getRemoteTipBlockNumber() == null
                ? localTip : number(status.getRemoteTipBlockNumber());
        double utxoApplied = 0;
        double utxoEnabled = 0;
        var utxo = ledgerQuery.getUtxoState();
        if (utxo instanceof UtxoStatusProvider provider && utxo.isEnabled()) {
            utxoEnabled = 1;
            utxoApplied = Math.max(0, provider.getLastAppliedBlock());
        }
        return new Snapshot(
                now,
                localTip,
                remoteTip,
                Math.max(0, remoteTip - localTip),
                number(status.getRelayInboundConnectionCount()),
                number(status.getRelayOutboundConnectionCount()),
                number(status.getRelayEstablishedConnectionCount()),
                number(status.getRelayColdPeerCount()),
                number(status.getRelayWarmPeerCount()),
                number(status.getRelayHotPeerCount()),
                number(status.getRelayBackoffPeerCount()),
                number(status.getRelayQuarantinedPeerCount()),
                number(status.getMempoolSize()),
                number(status.getMempoolBytes()),
                number(status.getMempoolMaxTxs()),
                number(status.getMempoolMaxBytes()),
                number(status.getTxDiffusionOutboundForwarded()),
                number(status.getTxDiffusionOutboundSuppressed()),
                number(status.getTxDiffusionInboundTxBodiesAccepted()),
                number(status.getTxDiffusionInboundTxBodiesRejected()),
                number(status.getTxDiffusionInboundTxBodiesIgnored()),
                number(status.getTxDiffusionServedTxs()),
                number(status.getTxDiffusionInFlightTxs()),
                number(status.getTxDiffusionInFlightBytes()),
                utxoEnabled,
                utxoApplied,
                utxoEnabled == 1 ? Math.max(0, localTip - utxoApplied) : 0);
    }

    private static double number(Number value) {
        return value == null ? 0 : Math.max(0, value.doubleValue());
    }

    private record Snapshot(
            long createdAtNanos,
            double localTip,
            double remoteTip,
            double syncGap,
            double inboundConnections,
            double outboundConnections,
            double establishedConnections,
            double coldPeers,
            double warmPeers,
            double hotPeers,
            double backoffPeers,
            double quarantinedPeers,
            double mempoolTransactions,
            double mempoolBytes,
            double mempoolMaxTransactions,
            double mempoolMaxBytes,
            double outboundForwarded,
            double outboundSuppressed,
            double inboundAccepted,
            double inboundRejected,
            double inboundIgnored,
            double served,
            double inflightTransactions,
            double inflightBytes,
            double utxoEnabled,
            double utxoLastApplied,
            double utxoLag) {
    }
}
