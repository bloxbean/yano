package com.bloxbean.cardano.yano.api.config;

import lombok.Builder;
import lombok.Data;

import java.util.Locale;
import java.util.Set;

/**
 * Chain-selection policy for multi-peer upstream modes.
 */
@Data
@Builder(toBuilder = true)
public class ChainSelectionConfig {
    private static final Set<String> SUPPORTED_POLICIES = Set.of("trusted-or-quorum-within-rollback-window");
    private static final Set<String> SUPPORTED_TRUST_POLICIES = Set.of("trusted-only", "quorum", "validated");
    private static final Set<String> SUPPORTED_TIE_BREAKS = Set.of("deterministic", "keep-current-ha-only");

    @Builder.Default
    private String policy = "trusted-or-quorum-within-rollback-window";
    /**
     * Slot window used to retain and evaluate non-canonical candidate headers.
     * A value of 0 means derive the window from Shelley genesis as ceil(k / f).
     */
    @Builder.Default
    private long rollbackWindowSlots = 0;
    @Builder.Default
    private boolean requireBodyBeforeAdoption = true;
    @Builder.Default
    private String trustPolicy = "trusted-only";
    @Builder.Default
    private int quorum = 2;
    @Builder.Default
    private String tieBreak = "deterministic";

    public void validate(UpstreamPreset preset) {
        if (!SUPPORTED_POLICIES.contains(normalize(policy, "trusted-or-quorum-within-rollback-window"))) {
            throw new IllegalArgumentException("Unsupported yano.upstream.selection.policy: " + policy);
        }
        if (rollbackWindowSlots < 0) {
            throw new IllegalArgumentException(
                    "yano.upstream.selection.rollback-window-slots must be non-negative");
        }
        if (quorum < 1) {
            throw new IllegalArgumentException("yano.upstream.selection.quorum must be positive");
        }
        if (!SUPPORTED_TRUST_POLICIES.contains(normalize(trustPolicy, "trusted-only"))) {
            throw new IllegalArgumentException("Unsupported yano.upstream.selection.trust-policy: " + trustPolicy);
        }
        String normalizedTieBreak = normalize(tieBreak, "deterministic");
        if (!SUPPORTED_TIE_BREAKS.contains(normalizedTieBreak)) {
            throw new IllegalArgumentException("Unsupported yano.upstream.selection.tie-break: " + tieBreak);
        }
        if (preset != null && preset.multiPeer()
                && "keep-current-ha-only".equals(normalizedTieBreak)) {
            throw new IllegalArgumentException("keep-current-ha-only tie break is valid only for single-active upstream presets");
        }
    }

    private static String normalize(String value, String defaultValue) {
        return value == null || value.isBlank()
                ? defaultValue
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
