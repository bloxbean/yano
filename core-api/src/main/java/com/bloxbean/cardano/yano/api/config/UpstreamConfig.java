package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Top-level upstream behavior configuration.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamConfig {
    @Builder.Default
    private UpstreamPreset mode = UpstreamPreset.TRUSTED_SINGLE;
    @Builder.Default
    private List<UpstreamPeerConfig> peers = List.of();
    @Builder.Default
    private ChainSelectionConfig selection = ChainSelectionConfig.builder().build();
    @Builder.Default
    private UpstreamValidationConfig validation = UpstreamValidationConfig.builder().build();
    @Builder.Default
    private UpstreamSyncConfig sync = UpstreamSyncConfig.builder().build();
    @Builder.Default
    private UpstreamFailoverConfig failover = UpstreamFailoverConfig.builder().build();
    @Builder.Default
    private UpstreamTxConfig tx = UpstreamTxConfig.builder().build();
    @Builder.Default
    private UpstreamGovernorConfig governor = UpstreamGovernorConfig.builder().build();
    @Builder.Default
    private UpstreamDiscoveryConfig discovery = UpstreamDiscoveryConfig.builder().build();

    public static UpstreamConfig trustedSingleFromRemote(String host, int port) {
        return UpstreamConfig.builder()
                .mode(UpstreamPreset.TRUSTED_SINGLE)
                .peers(List.of(UpstreamPeerConfig.builder()
                        .id("remote")
                        .host(host)
                        .port(port)
                        .source("legacy-remote")
                        .priority(0)
                        .trust("trusted")
                        .build()))
                .build();
    }

    public List<UpstreamPeerConfig> orderedPeers() {
        List<UpstreamPeerConfig> copy = new ArrayList<>(peers != null ? peers : List.of());
        copy.sort(Comparator
                .comparingInt(UpstreamPeerConfig::getPriority)
                .thenComparing(UpstreamPeerConfig::effectiveId));
        return copy;
    }

    public boolean discoveryBootstrapEnabled() {
        UpstreamPreset effectiveMode = mode != null ? mode : UpstreamPreset.TRUSTED_SINGLE;
        return effectiveMode.multiPeer()
                && discovery != null
                && discovery.isEnabled();
    }

    public void validate(boolean clientEnabled, String remoteHost, int remotePort, long protocolMagic) {
        UpstreamPreset effectiveMode = mode != null ? mode : UpstreamPreset.TRUSTED_SINGLE;
        List<UpstreamPeerConfig> effectivePeers = peers;
        if ((effectivePeers == null || effectivePeers.isEmpty())
                && remoteHost != null && !remoteHost.isBlank()
                && !discoveryBootstrapEnabled()) {
            effectivePeers = trustedSingleFromRemote(remoteHost, remotePort).getPeers();
        }

        if (!clientEnabled) {
            return;
        }
        if ((effectivePeers == null || effectivePeers.isEmpty()) && !discoveryBootstrapEnabled()) {
            throw new IllegalArgumentException("At least one upstream peer is required when client mode is enabled");
        }
        for (UpstreamPeerConfig peer : effectivePeers != null ? effectivePeers : List.<UpstreamPeerConfig>of()) {
            if (peer == null) {
                throw new IllegalArgumentException("Upstream peer entries must not be null");
            }
            peer.validate(protocolMagic);
        }

        if (selection != null) {
            selection.validate(effectiveMode);
        }
        if (validation != null) {
            validation.validate();
        }
        if (sync != null) {
            sync.validate();
        }
        if (failover != null) {
            failover.validate();
        }
        if (tx != null) {
            tx.validate();
        }
        if (governor != null) {
            governor.validate();
        }
        if (discovery != null) {
            discovery.validate();
        }

        if (effectiveMode.multiPeer()
                && validation != null
                && selection != null
                && "validated".equalsIgnoreCase(selection.getTrustPolicy())) {
            if (!validation.producesHeaderEvidence()) {
                throw new IllegalArgumentException(
                        "validated trust policy requires yano.upstream.validation.level other than none");
            }
        }
    }
}
