package com.bloxbean.cardano.yano.app.api.status;

import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NodeMetricsTest {

    @Test
    void registersBoundedMetricContractAndClampsLags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NodeLifecycle lifecycle = mock(NodeLifecycle.class);
        LedgerQuery ledger = mock(LedgerQuery.class);
        when(ledger.getUtxoState()).thenReturn(null);
        when(lifecycle.getStatus()).thenReturn(NodeStatus.builder()
                .localTipBlockNumber(120L)
                .remoteTipBlockNumber(100L)
                .relayInboundConnectionCount(2)
                .relayOutboundConnectionCount(3)
                .relayEstablishedConnectionCount(4)
                .relayColdPeerCount(5)
                .relayWarmPeerCount(6)
                .relayHotPeerCount(7)
                .relayBackoffPeerCount(8)
                .relayQuarantinedPeerCount(9)
                .mempoolSize(10)
                .mempoolBytes(11L)
                .mempoolMaxTxs(12)
                .mempoolMaxBytes(13L)
                .txDiffusionOutboundForwarded(14L)
                .txDiffusionOutboundSuppressed(15L)
                .txDiffusionInboundTxBodiesAccepted(16L)
                .txDiffusionInboundTxBodiesRejected(17L)
                .txDiffusionInboundTxBodiesIgnored(18L)
                .txDiffusionServedTxs(19L)
                .txDiffusionInFlightTxs(20L)
                .txDiffusionInFlightBytes(21L)
                .build());

        NodeMetrics metrics = new NodeMetrics();
        metrics.registry = registry;
        metrics.nodeLifecycle = lifecycle;
        metrics.ledgerQuery = ledger;
        metrics.register();

        Set<String> names = registry.getMeters().stream()
                .map(Meter::getId)
                .map(Meter.Id::getName)
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrderElementsOf(NodeMetrics.METRIC_NAMES);
        assertThat(registry.get("yano.node.sync.gap.blocks").gauge().value()).isZero();
        assertThat(registry.get("yano.node.utxo.enabled").gauge().value()).isZero();
        assertThat(registry.get("yano.node.utxo.lag.blocks").gauge().value()).isZero();

        assertThat(registry.get("yano.node.peers.connections").gauges())
                .extracting(gauge -> gauge.getId().getTag("type"))
                .containsExactlyInAnyOrder("inbound", "outbound", "established");
        assertThat(registry.get("yano.node.peers.governor").gauges())
                .extracting(gauge -> gauge.getId().getTag("state"))
                .containsExactlyInAnyOrder("cold", "warm", "hot", "backoff", "quarantined");
        assertThat(registry.get("yano.node.tx.diffusion.total").functionCounters())
                .extracting(counter -> counter.getId().getTag("outcome"))
                .containsExactlyInAnyOrder("outbound_forwarded", "outbound_suppressed",
                        "inbound_accepted", "inbound_rejected", "inbound_ignored", "served");
    }
}
