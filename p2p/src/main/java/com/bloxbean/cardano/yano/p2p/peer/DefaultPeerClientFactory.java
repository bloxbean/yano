package com.bloxbean.cardano.yano.p2p.peer;

import com.bloxbean.cardano.yaci.core.network.NodeClientConfig;
import com.bloxbean.cardano.yaci.core.network.SocketAddressFamily;
import com.bloxbean.cardano.yaci.core.network.SocketAddressResolutionMode;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.PeerClient;

import java.util.Objects;

/**
 * Default Yaci-backed peer client factory.
 */
public final class DefaultPeerClientFactory implements PeerClientFactory {
    private static final int SUPERVISED_CONNECT_TIMEOUT_MS = 15_000;

    private final NodeClientConfig nodeClientConfig;

    public DefaultPeerClientFactory(NodeClientConfig nodeClientConfig) {
        this.nodeClientConfig = Objects.requireNonNull(nodeClientConfig, "nodeClientConfig");
    }

    public static DefaultPeerClientFactory supervised() {
        return new DefaultPeerClientFactory(supervisedNodeClientConfig());
    }

    public static DefaultPeerClientFactory supervisedWithLocalBind(String localBindHost, int localBindPort) {
        return new DefaultPeerClientFactory(supervisedNodeClientConfig(localBindHost, localBindPort));
    }

    public static NodeClientConfig supervisedNodeClientConfig() {
        return supervisedNodeClientConfig(null, 0);
    }

    public static NodeClientConfig supervisedNodeClientConfig(String localBindHost, int localBindPort) {
        var builder = NodeClientConfig.builder()
                .autoReconnect(false)
                .connectionTimeoutMs(SUPERVISED_CONNECT_TIMEOUT_MS)
                .maxRetryAttempts(0)
                .propagateStartupFailure(true)
                .socketAddressResolutionMode(SocketAddressResolutionMode.DNS_ROTATING)
                .socketAddressFamily(SocketAddressFamily.IPV4_ONLY);
        if (localBindPort > 0) {
            builder.localBindHost(localBindHost)
                    .localBindPort(localBindPort)
                    .localBindFallbackToEphemeral(true);
        }
        return builder.build();
    }

    @Override
    public PeerClient create(PeerEndpoint endpoint, Point startPoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(startPoint, "startPoint");
        return new PeerClient(
                endpoint.host(),
                endpoint.port(),
                endpoint.protocolMagic(),
                startPoint,
                nodeClientConfig);
    }
}
