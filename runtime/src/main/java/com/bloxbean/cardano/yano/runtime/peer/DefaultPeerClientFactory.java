package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yaci.core.network.NodeClientConfig;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.PeerClient;

import java.util.Objects;

/**
 * Default Yaci-backed peer client factory.
 */
public final class DefaultPeerClientFactory implements PeerClientFactory {
    private static final int SUPERVISED_CONNECT_TIMEOUT_MS = 30_000;

    private final NodeClientConfig nodeClientConfig;

    public DefaultPeerClientFactory(NodeClientConfig nodeClientConfig) {
        this.nodeClientConfig = Objects.requireNonNull(nodeClientConfig, "nodeClientConfig");
    }

    public static DefaultPeerClientFactory supervised() {
        return new DefaultPeerClientFactory(supervisedNodeClientConfig());
    }

    public static NodeClientConfig supervisedNodeClientConfig() {
        return NodeClientConfig.builder()
                .autoReconnect(false)
                .connectionTimeoutMs(SUPERVISED_CONNECT_TIMEOUT_MS)
                .maxRetryAttempts(0)
                .propagateStartupFailure(true)
                .build();
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
