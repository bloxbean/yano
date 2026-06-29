package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;
import java.util.Objects;

/**
 * Configured upstream peer endpoint.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamPeerConfig {
    private String id;
    private String host;
    private int port;
    private String source;
    private int priority;
    private String trust;

    public String effectiveId() {
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        return host + ":" + port;
    }

    public boolean trusted() {
        return trust == null || trust.isBlank()
                || "trusted".equals(trust.trim().toLowerCase(Locale.ROOT));
    }

    public void validate(long protocolMagic) {
        Objects.requireNonNull(host, "upstream peer host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Upstream peer host must not be blank");
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("Upstream peer port must be between 1 and 65535");
        }
        if (protocolMagic < 0) {
            throw new IllegalArgumentException("Protocol magic must not be negative");
        }
    }
}
