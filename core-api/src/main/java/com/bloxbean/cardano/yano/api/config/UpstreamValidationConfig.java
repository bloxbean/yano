package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;
import java.util.Set;

/**
 * Upstream header/body validation level.
 */
@Data
@Builder(toBuilder = true)
public class UpstreamValidationConfig {
    private static final Set<String> SUPPORTED_HEADER_LEVELS = Set.of(
            "none", "structural", "header-signature", "praos-lite", "praos-ledger");
    private static final Set<String> SUPPORTED_BODY_LEVELS = Set.of("none");
    private static final Set<String> SUPPORTED_OPCERT_COUNTER_MODES = Set.of("none", "compat", "strict");

    @Builder.Default
    private String level = "none";
    @Builder.Default
    private String bodyLevel = "none";
    @Builder.Default
    private String opCertCounterMode = "none";
    @Builder.Default
    private UpstreamValidationStartConfig start = UpstreamValidationStartConfig.builder().build();

    public void validate() {
        String normalized = normalizedLevel();
        if (!SUPPORTED_HEADER_LEVELS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.validation.level: " + level
                    + " (supported now: none, structural, header-signature, praos-lite, praos-ledger)");
        }
        String normalizedBody = normalizedBodyLevel();
        if (!SUPPORTED_BODY_LEVELS.contains(normalizedBody)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.validation.body-level: " + bodyLevel
                    + " (supported now: none)");
        }
        String normalizedOpCertCounterMode = normalizedOpCertCounterMode();
        if (!SUPPORTED_OPCERT_COUNTER_MODES.contains(normalizedOpCertCounterMode)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.validation.opcert-counter-mode: "
                    + opCertCounterMode + " (supported now: none, compat, strict)");
        }
        if (start != null) {
            start.validate();
        }
    }

    public String normalizedLevel() {
        return level == null || level.isBlank()
                ? "none"
                : level.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedBodyLevel() {
        return bodyLevel == null || bodyLevel.isBlank()
                ? "none"
                : bodyLevel.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizedOpCertCounterMode() {
        return opCertCounterMode == null || opCertCounterMode.isBlank()
                ? "none"
                : opCertCounterMode.trim().toLowerCase(Locale.ROOT);
    }

    public boolean opCertCounterModeEnabled() {
        return !"none".equals(normalizedOpCertCounterMode());
    }

    public boolean strictOpCertCounterMode() {
        return "strict".equals(normalizedOpCertCounterMode());
    }

    public boolean producesHeaderEvidence() {
        return !"none".equals(normalizedLevel());
    }
}
