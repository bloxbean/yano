package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

/**
 * Peer-governor target configuration.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamGovernorConfig {
    @Builder.Default
    private boolean enabled = false;
    @Builder.Default
    private int targetCold = 100;
    @Builder.Default
    private int targetWarm = 8;
    @Builder.Default
    private int targetHot = 2;
    @Builder.Default
    private int maxConcurrentDials = 4;

    public void validate() {
        if (targetCold < 0) {
            throw new IllegalArgumentException("yano.upstream.governor.targets.cold must be non-negative");
        }
        if (targetWarm < 0) {
            throw new IllegalArgumentException("yano.upstream.governor.targets.warm must be non-negative");
        }
        if (targetHot < 1) {
            throw new IllegalArgumentException("yano.upstream.governor.targets.hot must be positive");
        }
        if (maxConcurrentDials < 1) {
            throw new IllegalArgumentException("yano.upstream.governor.max-concurrent-dials must be positive");
        }
    }
}
