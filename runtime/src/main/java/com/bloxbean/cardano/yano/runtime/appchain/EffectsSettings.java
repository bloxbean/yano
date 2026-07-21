package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;

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
        AppChainEffectsConfig parsed = AppChainEffectsConfig.fromSettings(settings);
        return new EffectsSettings(parsed.enabled(), parsed.maxPerBlock(),
                parsed.maxPayloadBytes(), parsed.maxExpiryBlocks(),
                parsed.resultWindowBlocks(), parsed.defaultGate(),
                parsed.outcomeCommitment(), parsed.strictReservedPrefix(),
                parsed.resultSigners());
    }

    /** Build the one normalized ADR-016 profile consumed by runtime and plugins. */
    AppChainConsensusProfile consensusProfile(AppChainConfig config) {
        return shared().consensusProfile(config);
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
        return shared().resultSignerAllowedHex(senderHex);
    }

    private AppChainEffectsConfig shared() {
        return new AppChainEffectsConfig(enabled, maxPerBlock, maxPayloadBytes,
                maxExpiryBlocks, resultWindowBlocks, defaultGate,
                outcomeCommitment, strictReservedPrefix, resultSigners);
    }
}
