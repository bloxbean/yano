package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsed {@code effects.*} chain settings (ADR app-layer/010 F12). The
 * consensus-affecting subset — {@code enabled}, {@code max-per-block},
 * {@code max-payload-bytes}, {@code max-expiry-blocks},
 * {@code result-window-blocks}, {@code default-gate},
 * {@code outcome-commitment}, {@code result.signers} (all read at apply time
 * by the kernel) AND {@code strict-reserved-prefix} (it changes which apply()
 * calls fail) — MUST be identical on every member: a mismatch diverges state
 * roots or block validity at the first emission, the same failure class as a
 * mismatched state machine.
 * <p>
 * {@code strict-reserved-prefix} applies even when effects are disabled: the
 * {@code ~fx/} trie keyspace is reserved from genesis (ADR-010 F4).
 */
record EffectsSettings(boolean enabled,
                       int maxPerBlock,
                       int maxPayloadBytes,
                       long maxExpiryBlocks,
                       long resultWindowBlocks,
                       FinalityGate defaultGate,
                       EffectOutcomeCommitment outcomeCommitment,
                       boolean strictReservedPrefix,
                       Set<String> resultSigners) {

    static EffectsSettings from(AppChainConfig config) {
        return fromSettings(config.pluginSettings());
    }

    static EffectsSettings fromSettings(Map<String, String> settings) {
        boolean strict = Boolean.parseBoolean(
                settings.getOrDefault("effects.strict-reserved-prefix", "true"));
        if (!Boolean.parseBoolean(settings.getOrDefault("effects.enabled", "false"))) {
            // Reserved-prefix enforcement is NOT effects-gated (see class javadoc)
            return new EffectsSettings(false, 0, 0, 0, 0, FinalityGate.APP_FINAL,
                    EffectOutcomeCommitment.PER_EFFECT, strict, Set.of());
        }
        FinalityGate defaultGate = switch (
                settings.getOrDefault("effects.default-gate", "app-final").trim().toLowerCase(Locale.ROOT)) {
            case "app-final" -> FinalityGate.APP_FINAL;
            case "l1-anchored" -> FinalityGate.L1_ANCHORED;
            case "zk-settled" -> FinalityGate.ZK_SETTLED;
            default -> throw new IllegalArgumentException(
                    "effects.default-gate must be 'app-final', 'l1-anchored' or 'zk-settled'");
        };
        // Result signer policy (ADR-010 F8, CONSENSUS-AFFECTING): empty = any
        // current member may attest outcomes; else only the listed member keys
        // (the designated executors' keys). Interpreter no-ops results from
        // anyone else — deterministic, never a stall.
        Set<String> resultSigners = Set.of();
        String signersValue = settings.getOrDefault("effects.result.signers", "").trim();
        if (!signersValue.isEmpty()) {
            Set<String> parsed = new java.util.LinkedHashSet<>();
            for (String key : signersValue.split("\\s*,\\s*")) {
                if (!key.isBlank()) {
                    String normalized = key.toLowerCase(Locale.ROOT);
                    if (!parsed.add(normalized)) {
                        throw new IllegalArgumentException(
                                "effects.result.signers contains duplicate key " + normalized);
                    }
                }
            }
            resultSigners = Set.copyOf(parsed);
        }
        EffectOutcomeCommitment commitment = switch (
                settings.getOrDefault("effects.outcome-commitment", "per-effect").trim().toLowerCase(Locale.ROOT)) {
            case "per-effect" -> EffectOutcomeCommitment.PER_EFFECT;
            case "per-block" -> EffectOutcomeCommitment.PER_BLOCK;
            default -> throw new IllegalArgumentException(
                    "effects.outcome-commitment must be 'per-effect' or 'per-block'");
        };
        int maxPerBlock = intSetting(settings, "effects.max-per-block", 256);
        int maxPayloadBytes = intSetting(settings, "effects.max-payload-bytes", 16384);
        long maxExpiryBlocks = longSetting(settings, "effects.max-expiry-blocks", 100_000);
        // The interpreter rejects results for effects older than this window
        // DETERMINISTICALLY, before any CF lookup — node-local fx pruning can
        // therefore never influence incorporation (ADR-010 F8; M2 review).
        long resultWindowBlocks = longSetting(settings, "effects.result-window-blocks", 100_000);
        if (maxPerBlock <= 0 || maxPayloadBytes <= 0 || maxExpiryBlocks <= 0
                || resultWindowBlocks <= 0) {
            throw new IllegalArgumentException("effects.max-per-block, effects.max-payload-bytes, "
                    + "effects.max-expiry-blocks and effects.result-window-blocks must be positive");
        }
        if (maxPayloadBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "effects.max-payload-bytes must be <= 16777216; use payload-by-hash for larger bodies");
        }
        if (maxPerBlock > com.bloxbean.cardano.yano.api.appchain.effects.FxKeys.MAX_EFFECTS_PER_BLOCK) {
            // Ordinals pack into 20 bits in the kernel's in-block dedup key
            throw new IllegalArgumentException("effects.max-per-block must be <= 1048576");
        }
        return new EffectsSettings(true, maxPerBlock, maxPayloadBytes, maxExpiryBlocks,
                resultWindowBlocks, defaultGate, commitment, strict, resultSigners);
    }

    /** Build the one normalized ADR-016 profile consumed by runtime and plugins. */
    AppChainConsensusProfile consensusProfile(AppChainConfig config) {
        Set<String> genesisMembers = new HashSet<>();
        for (String member : config.memberKeysHex()) {
            if (member != null) {
                genesisMembers.add(member.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String signer : resultSigners) {
            if (!genesisMembers.contains(signer)) {
                throw new IllegalArgumentException("effects.result.signers contains nonmember key "
                        + signer);
            }
        }
        return new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                config.maxMessageBytes(),
                config.maxBlockMessages(),
                config.blockMaxBytes(),
                config.l1StabilityDepth(),
                config.enforceSenderSeq(),
                enabled,
                maxPerBlock,
                maxPayloadBytes,
                maxExpiryBlocks,
                resultWindowBlocks,
                defaultGate,
                outcomeCommitment,
                strictReservedPrefix,
                resultSigners.stream().sorted().toList());
    }

    /** Shared designated-signer policy for proposal admission and incorporation. */
    boolean resultSignerAllowed(byte[] sender) {
        if (resultSigners.isEmpty()) {
            return true;
        }
        if (sender == null || sender.length == 0) {
            return false;
        }
        String senderHex = com.bloxbean.cardano.yaci.core.util.HexUtil
                .encodeHexString(sender).toLowerCase(Locale.ROOT);
        return resultSigners.contains(senderHex);
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
