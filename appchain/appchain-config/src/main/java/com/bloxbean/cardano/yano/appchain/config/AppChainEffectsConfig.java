package com.bloxbean.cardano.yano.appchain.config;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.FxKeys;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared, side-effect-free parser for the consensus-visible {@code effects.*} settings. */
public record AppChainEffectsConfig(
        boolean enabled,
        int maxPerBlock,
        int maxPayloadBytes,
        long maxExpiryBlocks,
        long resultWindowBlocks,
        FinalityGate defaultGate,
        EffectOutcomeCommitment outcomeCommitment,
        boolean strictReservedPrefix,
        Set<String> resultSigners) {

    public static final int DEFAULT_MAX_PER_BLOCK = 256;
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 16_384;
    public static final long DEFAULT_MAX_EXPIRY_BLOCKS = 100_000;
    public static final long DEFAULT_RESULT_WINDOW_BLOCKS = 100_000;
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    public AppChainEffectsConfig {
        resultSigners = resultSigners == null ? Set.of() : Set.copyOf(resultSigners);
    }

    public static AppChainEffectsConfig from(AppChainConfig config) {
        return fromSettings(config.pluginSettings());
    }

    public static AppChainEffectsConfig fromSettings(Map<String, String> settings) {
        boolean strict = Boolean.parseBoolean(
                settings.getOrDefault("effects.strict-reserved-prefix", "true"));
        if (!Boolean.parseBoolean(settings.getOrDefault("effects.enabled", "false"))) {
            return new AppChainEffectsConfig(false, 0, 0, 0, 0, FinalityGate.APP_FINAL,
                    EffectOutcomeCommitment.PER_EFFECT, strict, Set.of());
        }
        FinalityGate defaultGate = switch (
                settings.getOrDefault("effects.default-gate", "app-final")
                        .trim().toLowerCase(Locale.ROOT)) {
            case "app-final" -> FinalityGate.APP_FINAL;
            case "l1-anchored" -> FinalityGate.L1_ANCHORED;
            case "zk-settled" -> FinalityGate.ZK_SETTLED;
            default -> throw new IllegalArgumentException(
                    "effects.default-gate must be 'app-final', 'l1-anchored' or 'zk-settled'");
        };
        Set<String> resultSigners = parseResultSigners(settings);
        EffectOutcomeCommitment commitment = switch (
                settings.getOrDefault("effects.outcome-commitment", "per-effect")
                        .trim().toLowerCase(Locale.ROOT)) {
            case "per-effect" -> EffectOutcomeCommitment.PER_EFFECT;
            case "per-block" -> EffectOutcomeCommitment.PER_BLOCK;
            default -> throw new IllegalArgumentException(
                    "effects.outcome-commitment must be 'per-effect' or 'per-block'");
        };
        int maxPerBlock = intSetting(settings, "effects.max-per-block", DEFAULT_MAX_PER_BLOCK);
        int maxPayloadBytes = intSetting(
                settings, "effects.max-payload-bytes", DEFAULT_MAX_PAYLOAD_BYTES);
        long maxExpiryBlocks = longSetting(
                settings, "effects.max-expiry-blocks", DEFAULT_MAX_EXPIRY_BLOCKS);
        long resultWindowBlocks = longSetting(
                settings, "effects.result-window-blocks", DEFAULT_RESULT_WINDOW_BLOCKS);
        if (maxPerBlock <= 0 || maxPayloadBytes <= 0 || maxExpiryBlocks <= 0
                || resultWindowBlocks <= 0) {
            throw new IllegalArgumentException("effects.max-per-block, effects.max-payload-bytes, "
                    + "effects.max-expiry-blocks and effects.result-window-blocks must be positive");
        }
        if (maxPayloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "effects.max-payload-bytes must be <= 16777216; use payload-by-hash for larger bodies");
        }
        if (maxPerBlock > FxKeys.MAX_EFFECTS_PER_BLOCK) {
            throw new IllegalArgumentException("effects.max-per-block must be <= 1048576");
        }
        return new AppChainEffectsConfig(true, maxPerBlock, maxPayloadBytes, maxExpiryBlocks,
                resultWindowBlocks, defaultGate, commitment, strict, resultSigners);
    }

    /** Build the canonical consensus profile and validate designated signers against genesis members. */
    public AppChainConsensusProfile consensusProfile(AppChainConfig config) {
        Set<String> genesisMembers = new HashSet<>();
        for (String member : config.memberKeysHex()) {
            if (member != null) {
                genesisMembers.add(member.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String signer : resultSigners) {
            if (!genesisMembers.contains(signer)) {
                throw new IllegalArgumentException(
                        "effects.result.signers contains nonmember key " + signer);
            }
        }
        return new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                config.maxMessageBytes(), config.maxBlockMessages(), config.blockMaxBytes(),
                config.l1StabilityDepth(), config.enforceSenderSeq(), enabled, maxPerBlock,
                maxPayloadBytes, maxExpiryBlocks, resultWindowBlocks, defaultGate,
                outcomeCommitment, strictReservedPrefix,
                resultSigners.stream().sorted().toList());
    }

    public boolean resultSignerAllowedHex(String senderHex) {
        return resultSigners.isEmpty()
                || senderHex != null && resultSigners.contains(senderHex.toLowerCase(Locale.ROOT));
    }

    private static Set<String> parseResultSigners(Map<String, String> settings) {
        String value = settings.getOrDefault("effects.result.signers", "").trim();
        if (value.isEmpty()) {
            return Set.of();
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String key : value.split("\\s*,\\s*")) {
            if (!key.isBlank()) {
                String normalized = key.toLowerCase(Locale.ROOT);
                if (!parsed.add(normalized)) {
                    throw new IllegalArgumentException(
                            "effects.result.signers contains duplicate key " + normalized);
                }
            }
        }
        return Set.copyOf(parsed);
    }

    private static int intSetting(Map<String, String> settings, String key, int defaultValue) {
        String value = settings.get(key);
        return value != null && !value.isBlank() ? Integer.parseInt(value.trim()) : defaultValue;
    }

    private static long longSetting(Map<String, String> settings, String key, long defaultValue) {
        String value = settings.get(key);
        return value != null && !value.isBlank() ? Long.parseLong(value.trim()) : defaultValue;
    }
}
