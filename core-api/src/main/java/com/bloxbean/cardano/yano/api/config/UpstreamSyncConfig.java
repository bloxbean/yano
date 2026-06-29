package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;
import java.util.Set;

/**
 * Sync-shaping options for upstream modes.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamSyncConfig {
    private static final Set<String> SUPPORTED_BULK_SOURCES = Set.of("single-trusted", "selected-candidate");
    private static final Set<String> SUPPORTED_FAN_IN_STARTS = Set.of("disabled", "near-tip", "always");

    @Builder.Default
    private String bulkSource = "single-trusted";
    @Builder.Default
    private String fanInStart = "near-tip";

    public void validate() {
        if (!SUPPORTED_BULK_SOURCES.contains(normalize(bulkSource, "single-trusted"))) {
            throw new IllegalArgumentException("Unsupported yano.upstream.sync.bulk-source: " + bulkSource);
        }
        if (!SUPPORTED_FAN_IN_STARTS.contains(normalize(fanInStart, "near-tip"))) {
            throw new IllegalArgumentException("Unsupported yano.upstream.sync.fan-in-start: " + fanInStart);
        }
    }

    private static String normalize(String value, String defaultValue) {
        return value == null || value.isBlank()
                ? defaultValue
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
