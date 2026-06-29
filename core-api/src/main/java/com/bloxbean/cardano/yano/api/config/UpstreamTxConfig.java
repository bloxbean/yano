package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;
import java.util.Set;

/**
 * Upstream transaction forwarding policy.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamTxConfig {
    private static final Set<String> SUPPORTED_FORWARDING = Set.of("active-selected", "all-hot-trusted", "disabled");

    @Builder.Default
    private String forwarding = "active-selected";

    public void validate() {
        if (!SUPPORTED_FORWARDING.contains(normalizedForwarding())) {
            throw new IllegalArgumentException("Unsupported yano.upstream.tx.forwarding: " + forwarding);
        }
    }

    public String normalizedForwarding() {
        return forwarding == null || forwarding.isBlank()
                ? "active-selected"
                : forwarding.trim().toLowerCase(Locale.ROOT);
    }
}
