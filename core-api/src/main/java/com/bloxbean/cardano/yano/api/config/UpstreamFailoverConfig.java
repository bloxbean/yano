package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

/**
 * Single-active failover policy.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamFailoverConfig {
    @Builder.Default
    private long cooldownMs = 30_000;
    @Builder.Default
    private int maxFailuresBeforeCooldown = 3;

    public void validate() {
        if (cooldownMs < 0) {
            throw new IllegalArgumentException("yano.upstream.failover.cooldown-ms must be non-negative");
        }
        if (maxFailuresBeforeCooldown < 1) {
            throw new IllegalArgumentException("yano.upstream.failover.max-failures-before-cooldown must be positive");
        }
    }
}
