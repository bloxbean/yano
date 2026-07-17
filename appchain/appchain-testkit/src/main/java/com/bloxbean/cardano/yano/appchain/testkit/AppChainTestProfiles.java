package com.bloxbean.cardano.yano.appchain.testkit;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Test-only factories for contexts that exercise ADR-016 typed limits. */
public final class AppChainTestProfiles {
    private AppChainTestProfiles() {
    }

    public static AppChainConsensusProfile enabledEffects(int effectsMaxPerBlock) {
        return enabledEffects(effectsMaxPerBlock, 16_384, 100_000, 100_000);
    }

    public static AppChainConsensusProfile fromSettings(Map<String, String> settings) {
        Map<String, String> values = settings != null ? settings : Map.of();
        boolean enabled = Boolean.parseBoolean(values.getOrDefault("effects.enabled", "false"));
        if (!enabled) {
            return new AppChainConsensusProfile(
                    AppChainConsensusProfile.SCHEMA_VERSION,
                    AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES, 1,
                    AppChainConfig.DEFAULT_BLOCK_MAX_BYTES, 0, false,
                    false, 0, 0, 0, 0, FinalityGate.APP_FINAL,
                    EffectOutcomeCommitment.PER_EFFECT, true, List.of());
        }
        int maxPerBlock = Integer.parseInt(values.getOrDefault(
                "effects.max-per-block", "256").trim());
        int maxPayloadBytes = Integer.parseInt(values.getOrDefault(
                "effects.max-payload-bytes", "16384").trim());
        long maxExpiryBlocks = Long.parseLong(values.getOrDefault(
                "effects.max-expiry-blocks", "100000").trim());
        long resultWindowBlocks = Long.parseLong(values.getOrDefault(
                "effects.result-window-blocks", "100000").trim());
        FinalityGate gate = switch (values.getOrDefault(
                "effects.default-gate", "app-final").trim().toLowerCase(Locale.ROOT)) {
            case "app-final" -> FinalityGate.APP_FINAL;
            case "l1-anchored" -> FinalityGate.L1_ANCHORED;
            case "zk-settled" -> FinalityGate.ZK_SETTLED;
            default -> throw new IllegalArgumentException("invalid test effect gate");
        };
        EffectOutcomeCommitment outcome = switch (values.getOrDefault(
                "effects.outcome-commitment", "per-effect").trim().toLowerCase(Locale.ROOT)) {
            case "per-effect" -> EffectOutcomeCommitment.PER_EFFECT;
            case "per-block" -> EffectOutcomeCommitment.PER_BLOCK;
            default -> throw new IllegalArgumentException("invalid test effect outcome commitment");
        };
        return new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES, 1,
                AppChainConfig.DEFAULT_BLOCK_MAX_BYTES, 0, false,
                true, maxPerBlock, maxPayloadBytes, maxExpiryBlocks,
                resultWindowBlocks, gate, outcome, true, List.of());
    }

    private static AppChainConsensusProfile enabledEffects(
            int effectsMaxPerBlock,
            int effectsMaxPayloadBytes,
            long effectsMaxExpiryBlocks,
            long effectsResultWindowBlocks
    ) {
        return new AppChainConsensusProfile(
                AppChainConsensusProfile.SCHEMA_VERSION,
                AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES,
                1,
                AppChainConfig.DEFAULT_BLOCK_MAX_BYTES,
                0,
                false,
                true,
                effectsMaxPerBlock,
                effectsMaxPayloadBytes,
                effectsMaxExpiryBlocks,
                effectsResultWindowBlocks,
                FinalityGate.APP_FINAL,
                EffectOutcomeCommitment.PER_EFFECT,
                true,
                List.of());
    }
}
