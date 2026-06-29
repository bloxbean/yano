package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Peer discovery configuration for rooted and P2P relay presets.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamDiscoveryConfig {
    @Builder.Default
    private boolean enabled = false;
    @Builder.Default
    private boolean peerSharing = false;
    @Builder.Default
    private List<String> seeds = List.of();
    @Builder.Default
    private List<String> peerSnapshotUrls = List.of();
    @Builder.Default
    private List<String> peerSnapshotFiles = List.of();
    @Builder.Default
    private int peerSnapshotLimit = 128;
    @Builder.Default
    private boolean allowPrivateAddresses = false;
    @Builder.Default
    private List<String> allowlist = List.of();
    @Builder.Default
    private List<String> denylist = List.of();

    public void validate() {
        if (peerSnapshotLimit < 1) {
            throw new IllegalArgumentException("yano.upstream.discovery.peer-snapshot-limit must be positive");
        }
    }
}
