package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared parser for the stock approvals machine's generic on-approved effect. */
public record AppChainApprovalsConfig(boolean enabled,
                                      String type,
                                      long expiryBlocks,
                                      FinalityGate gate,
                                      int maxPayloadBytes) {
    public static final String FEATURE = "on-approved-effect";
    public static final String PREFIX = "machines.approvals.on-approved-effect.";
    public static final String ENABLED = PREFIX + "enabled";
    public static final String TYPE = PREFIX + "type";
    public static final String GATE = PREFIX + "gate";
    public static final String EXPIRY_BLOCKS = PREFIX + "expiry-blocks";
    public static final String ACTIVATION = "machines.approvals.activations." + FEATURE;

    public static final AppChainApprovalsConfig DISABLED =
            new AppChainApprovalsConfig(false, "", 0, FinalityGate.CHAIN_DEFAULT, 0);

    private static final String LEGACY_PAYMENTS = "machines.approvals.payments";
    private static final String LEGACY_PAYMENT_PREFIX = "machines.approvals.payment-";
    private static final String LEGACY_PAYMENT_ACTIVATION =
            "machines.approvals.activations.payments";
    private static final Set<String> KEYS = Set.of(ENABLED, TYPE, GATE, EXPIRY_BLOCKS);

    public AppChainApprovalsConfig {
        if (!enabled) {
            if (type == null || !type.isEmpty() || expiryBlocks != 0
                    || gate != FinalityGate.CHAIN_DEFAULT || maxPayloadBytes != 0) {
                throw new IllegalArgumentException(
                        "disabled approvals effect configuration must use the disabled sentinel");
            }
        } else {
            if (type == null || type.isBlank() || !type.equals(type.trim())) {
                throw new IllegalArgumentException(TYPE
                        + " must be a non-blank, whitespace-trimmed routing type");
            }
            EffectIntent.of(type, new byte[0]).build();
            if (expiryBlocks < 0) {
                throw new IllegalArgumentException(EXPIRY_BLOCKS + " must be >= 0");
            }
            if (gate == null) {
                throw new IllegalArgumentException(GATE + " is required");
            }
            if (maxPayloadBytes <= 0
                    || maxPayloadBytes > AppChainEffectsConfig.MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("effects.max-payload-bytes must be between 1 and "
                        + AppChainEffectsConfig.MAX_PAYLOAD_BYTES);
            }
        }
    }

    public static AppChainApprovalsConfig fromSettings(Map<String, String> settings) {
        Map<String, String> values = settings != null ? settings : Map.of();
        rejectLegacyPaymentSettings(values);
        rejectUnknownSettings(values);

        boolean enabled = strictBoolean(values.getOrDefault(ENABLED, "false"), ENABLED);
        if (!enabled) {
            boolean partial = values.keySet().stream()
                    .anyMatch(key -> key.startsWith(PREFIX) && !ENABLED.equals(key));
            if (partial || values.containsKey(ACTIVATION)) {
                throw new IllegalArgumentException(ENABLED
                        + " must be true when generic effect settings or activation are present");
            }
            return DISABLED;
        }

        if (!strictBoolean(values.getOrDefault("effects.enabled", "false"),
                "effects.enabled")) {
            throw new IllegalArgumentException(
                    "effects.enabled must be true when " + ENABLED + " is true");
        }
        String type = required(values, TYPE);

        FinalityGate gate = switch (values.getOrDefault(GATE, "chain-default")
                .trim().toLowerCase(Locale.ROOT)) {
            case "chain-default" -> FinalityGate.CHAIN_DEFAULT;
            case "app-final" -> FinalityGate.APP_FINAL;
            case "l1-anchored" -> FinalityGate.L1_ANCHORED;
            case "zk-settled" -> FinalityGate.ZK_SETTLED;
            default -> throw new IllegalArgumentException(GATE
                    + " must be chain-default|app-final|l1-anchored|zk-settled");
        };
        long expiry = nonNegativeLong(values.getOrDefault(EXPIRY_BLOCKS, "0"),
                EXPIRY_BLOCKS);
        positiveLong(required(values, ACTIVATION), ACTIVATION);
        long maximumExpiry = positiveLong(
                values.getOrDefault("effects.max-expiry-blocks", "100000"),
                "effects.max-expiry-blocks");
        long resultWindow = positiveLong(
                values.getOrDefault("effects.result-window-blocks", "100000"),
                "effects.result-window-blocks");
        if (expiry > maximumExpiry || expiry > resultWindow) {
            throw new IllegalArgumentException(EXPIRY_BLOCKS
                    + " must not exceed effects.max-expiry-blocks or "
                    + "effects.result-window-blocks");
        }
        int maxPayloadBytes = positiveInt(values.getOrDefault(
                "effects.max-payload-bytes",
                Integer.toString(AppChainEffectsConfig.DEFAULT_MAX_PAYLOAD_BYTES)),
                "effects.max-payload-bytes");
        if (maxPayloadBytes > AppChainEffectsConfig.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("effects.max-payload-bytes must be <= "
                    + AppChainEffectsConfig.MAX_PAYLOAD_BYTES);
        }
        return new AppChainApprovalsConfig(true, type, expiry, gate, maxPayloadBytes);
    }

    private static void rejectLegacyPaymentSettings(Map<String, String> settings) {
        Optional<String> legacy = settings.keySet().stream()
                .filter(key -> LEGACY_PAYMENTS.equals(key)
                        || key.startsWith(LEGACY_PAYMENT_PREFIX)
                        || LEGACY_PAYMENT_ACTIVATION.equals(key))
                .sorted().findFirst();
        if (legacy.isPresent()) {
            throw new IllegalArgumentException("Unsupported pre-release approvals setting '"
                    + legacy.orElseThrow() + "'; use " + PREFIX + "* and " + ACTIVATION);
        }
    }

    private static void rejectUnknownSettings(Map<String, String> settings) {
        Optional<String> unknown = settings.keySet().stream()
                .filter(key -> key.startsWith(PREFIX) && !KEYS.contains(key))
                .sorted().findFirst();
        if (unknown.isPresent()) {
            throw new IllegalArgumentException(
                    "Unknown generic approvals effect setting: " + unknown.orElseThrow());
        }
    }

    private static boolean strictBoolean(String raw, String key) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false");
        };
    }

    private static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private static long nonNegativeLong(String raw, String key) {
        long value = decimalLong(raw, key);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be >= 0");
        }
        return value;
    }

    private static long positiveLong(String raw, String key) {
        long value = decimalLong(raw, key);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be a positive block height");
        }
        return value;
    }

    private static int positiveInt(String raw, String key) {
        long value = decimalLong(raw, key);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is outside the supported range");
        }
        return (int) value;
    }

    private static long decimalLong(String raw, String key) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[0-9]+")) {
            throw new IllegalArgumentException(key + " must be a decimal integer");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(key + " is outside the supported range", malformed);
        }
    }
}
