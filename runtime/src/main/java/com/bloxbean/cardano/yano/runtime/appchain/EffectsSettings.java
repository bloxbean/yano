package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.util.Locale;
import java.util.Map;

/**
 * Parsed {@code effects.*} chain settings (ADR app-layer/010 F12). The
 * consensus-affecting subset ({@code enabled}, {@code max-per-block},
 * {@code max-payload-bytes}, {@code default-gate}, {@code outcome-commitment})
 * MUST be identical on every member — a mismatch diverges state roots at the
 * first emission, the same failure class as a mismatched state machine.
 */
record EffectsSettings(boolean enabled,
                       int maxPerBlock,
                       int maxPayloadBytes,
                       FinalityGate defaultGate,
                       OutcomeCommitment outcomeCommitment,
                       boolean strictReservedPrefix) {

    enum OutcomeCommitment {
        /** One {@code ~fx/done} leaf per incorporated outcome — O(1) open/closed proofs. */
        PER_EFFECT,
        /** One {@code ~fx/results/<height>} leaf per block — trie growth O(effectful blocks). */
        PER_BLOCK
    }

    static final EffectsSettings DISABLED =
            new EffectsSettings(false, 0, 0, FinalityGate.APP_FINAL, OutcomeCommitment.PER_EFFECT, true);

    static EffectsSettings from(AppChainConfig config) {
        return fromSettings(config.pluginSettings());
    }

    static EffectsSettings fromSettings(Map<String, String> settings) {
        if (!Boolean.parseBoolean(settings.getOrDefault("effects.enabled", "false"))) {
            return DISABLED;
        }
        FinalityGate defaultGate = switch (
                settings.getOrDefault("effects.default-gate", "app-final").trim().toLowerCase(Locale.ROOT)) {
            case "app-final" -> FinalityGate.APP_FINAL;
            case "l1-anchored" -> FinalityGate.L1_ANCHORED;
            default -> throw new IllegalArgumentException(
                    "effects.default-gate must be 'app-final' or 'l1-anchored'");
        };
        OutcomeCommitment commitment = switch (
                settings.getOrDefault("effects.outcome-commitment", "per-effect").trim().toLowerCase(Locale.ROOT)) {
            case "per-effect" -> OutcomeCommitment.PER_EFFECT;
            case "per-block" -> OutcomeCommitment.PER_BLOCK;
            default -> throw new IllegalArgumentException(
                    "effects.outcome-commitment must be 'per-effect' or 'per-block'");
        };
        int maxPerBlock = intSetting(settings, "effects.max-per-block", 256);
        int maxPayloadBytes = intSetting(settings, "effects.max-payload-bytes", 16384);
        if (maxPerBlock <= 0 || maxPayloadBytes <= 0) {
            throw new IllegalArgumentException(
                    "effects.max-per-block and effects.max-payload-bytes must be positive");
        }
        return new EffectsSettings(true, maxPerBlock, maxPayloadBytes, defaultGate, commitment,
                Boolean.parseBoolean(settings.getOrDefault("effects.strict-reserved-prefix", "true")));
    }

    private static int intSetting(Map<String, String> settings, String key, int defaultValue) {
        String value = settings.get(key);
        return value != null && !value.isBlank() ? Integer.parseInt(value.trim()) : defaultValue;
    }
}
