package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;
import java.util.Set;

/**
 * Controls where upstream header validation begins.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamValidationStartConfig {
    private static final Set<String> SUPPORTED_MODES = Set.of("immediate", "era", "checkpoint");
    private static final Set<String> SUPPORTED_ERAS = Set.of("conway");

    @Builder.Default
    private String mode = "immediate";
    @Builder.Default
    private String era = "conway";
    @Builder.Default
    private long slot = -1;
    @Builder.Default
    private String hash = "";

    public void validate() {
        String normalizedMode = normalizedMode();
        if (!SUPPORTED_MODES.contains(normalizedMode)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.validation.start.mode: " + mode
                    + " (supported now: immediate, era, checkpoint)");
        }

        String normalizedEra = normalizedEra();
        if ("era".equals(normalizedMode) && !SUPPORTED_ERAS.contains(normalizedEra)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.validation.start.era: " + era
                    + " (supported now: conway)");
        }

        if ("checkpoint".equals(normalizedMode)) {
            if (slot < 0 || hash == null || hash.isBlank()) {
                throw new IllegalArgumentException(
                        "checkpoint validation start requires yano.upstream.validation.start.slot and hash");
            }
        }
    }

    public String normalizedMode() {
        return mode == null || mode.isBlank()
                ? "immediate"
                : mode.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedEra() {
        return era == null || era.isBlank()
                ? "conway"
                : era.trim().toLowerCase(Locale.ROOT);
    }

    public boolean hasCheckpoint() {
        return slot >= 0 && hash != null && !hash.isBlank();
    }
}
