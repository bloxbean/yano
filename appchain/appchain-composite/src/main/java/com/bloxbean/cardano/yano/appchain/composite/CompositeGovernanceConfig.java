package com.bloxbean.cardano.yano.appchain.composite;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Immutable consensus settings for fixed or governed composite profiles. */
public record CompositeGovernanceConfig(ProfileMode mode,
                                        int minimumActivationLag,
                                        int proposalTtlBlocks,
                                        int maximumEpochs,
                                        int resultDrainBlocks) {
    public static final int VERSION = 1;
    public static final int DEFAULT_MINIMUM_ACTIVATION_LAG = 20;
    public static final int DEFAULT_PROPOSAL_TTL_BLOCKS = 600;
    public static final int DEFAULT_MAXIMUM_EPOCHS = 1_024;

    public CompositeGovernanceConfig {
        if (mode == null || minimumActivationLag < 1 || minimumActivationLag > 100_000
                || proposalTtlBlocks < minimumActivationLag || proposalTtlBlocks > 1_000_000
                || maximumEpochs < 1 || maximumEpochs > 65_536
                || resultDrainBlocks < 0 || resultDrainBlocks > 10_000_000) {
            throw new IllegalArgumentException("invalid composite governance configuration");
        }
    }

    public static CompositeGovernanceConfig from(
            Map<String, String> settings, int resultDrainBlocks
    ) {
        Map<String, String> safe = settings != null ? Map.copyOf(settings) : Map.of();
        ProfileMode mode = ProfileMode.parse(safe.getOrDefault(
                "machines.composite.profile-mode", "fixed"));
        return new CompositeGovernanceConfig(mode,
                integer(safe, "machines.composite.profile-governance.min-activation-lag",
                        DEFAULT_MINIMUM_ACTIVATION_LAG),
                integer(safe, "machines.composite.profile-governance.proposal-ttl-blocks",
                        DEFAULT_PROPOSAL_TTL_BLOCKS),
                integer(safe, "machines.composite.profile-governance.max-epochs",
                        DEFAULT_MAXIMUM_EPOCHS), resultDrainBlocks);
    }

    public byte[] canonicalBytes() {
        byte[] domain = "yano-composite-governance-config-v1\0"
                .getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(domain.length + Integer.BYTES * 6)
                .put(domain).putInt(VERSION).putInt(mode.code)
                .putInt(minimumActivationLag).putInt(proposalTtlBlocks)
                .putInt(maximumEpochs).putInt(resultDrainBlocks).array();
    }

    private static int integer(Map<String, String> settings, String key, int defaultValue) {
        String value = settings.get(key);
        if (value == null) return defaultValue;
        if (!value.matches("0|[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException("invalid composite governance integer: " + key);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid composite governance integer: " + key);
        }
    }

    public enum ProfileMode {
        FIXED(0), GOVERNED(1);

        private final int code;

        ProfileMode(int code) {
            this.code = code;
        }

        static ProfileMode parse(String value) {
            return switch (value != null ? value.trim() : "") {
                case "fixed" -> FIXED;
                case "governed" -> GOVERNED;
                default -> throw new IllegalArgumentException(
                        "machines.composite.profile-mode must be fixed or governed");
            };
        }
    }
}
