package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcomeCommitment;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalized framework-owned settings that affect app-block validity or
 * deterministic state application (ADR app-layer/016).
 *
 * <p>This is intentionally narrower than a complete chain/deployment
 * manifest. Machine-specific configuration, credentials, targets and local
 * operations settings do not belong here.</p>
 */
public record AppChainConsensusProfile(
        int schemaVersion,
        int maxMessageBytes,
        int maxBlockMessages,
        long maxBlockBytes,
        int l1StabilityDepth,
        boolean enforceSenderSeq,
        boolean effectsEnabled,
        int effectsMaxPerBlock,
        int effectsMaxPayloadBytes,
        long effectsMaxExpiryBlocks,
        long effectsResultWindowBlocks,
        FinalityGate effectsDefaultGate,
        EffectOutcomeCommitment effectsOutcomeCommitment,
        boolean effectsStrictReservedPrefix,
        List<String> effectResultSigners
) {
    public static final int SCHEMA_VERSION = 1;
    private static final Pattern MEMBER_KEY = Pattern.compile("[0-9a-f]{64}");

    public AppChainConsensusProfile {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("consensus profile schemaVersion must be 1");
        }
        if (maxMessageBytes <= 0 || maxMessageBytes > AppChainConfig.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("maxMessageBytes is outside the app-chain v1 profile");
        }
        if (maxBlockMessages <= 0 || maxBlockMessages > AppChainConfig.MAX_BLOCK_MESSAGES) {
            throw new IllegalArgumentException("maxBlockMessages is outside the app-chain v1 profile");
        }
        if (maxBlockBytes <= 0 || maxBlockBytes > AppChainConfig.MAX_BLOCK_BYTES) {
            throw new IllegalArgumentException("maxBlockBytes is outside the app-chain v1 profile");
        }
        long requiredSingleMessageBytes = (long) maxMessageBytes
                + AppChainConfig.BLOCK_ENVELOPE_HEADROOM_BYTES
                + AppChainConfig.MAX_FINALITY_CERT_HEADROOM_BYTES;
        if (maxBlockBytes < requiredSingleMessageBytes) {
            throw new IllegalArgumentException(
                    "maxBlockBytes does not reserve finalized envelope/certificate headroom");
        }
        if (l1StabilityDepth < 0) {
            throw new IllegalArgumentException("l1StabilityDepth must be nonnegative");
        }
        effectsDefaultGate = Objects.requireNonNull(effectsDefaultGate, "effectsDefaultGate");
        if (effectsDefaultGate == FinalityGate.CHAIN_DEFAULT) {
            throw new IllegalArgumentException(
                    "consensus profile must contain a resolved effect default gate");
        }
        effectsOutcomeCommitment = Objects.requireNonNull(
                effectsOutcomeCommitment, "effectsOutcomeCommitment");

        if (effectsEnabled) {
            if (effectsMaxPerBlock <= 0
                    || effectsMaxPerBlock > (1 << 20)
                    || effectsMaxPayloadBytes <= 0
                    || effectsMaxPayloadBytes > 16 * 1024 * 1024
                    || effectsMaxExpiryBlocks <= 0
                    || effectsResultWindowBlocks <= 0) {
                throw new IllegalArgumentException(
                        "enabled effect consensus limits must be positive and within v1 bounds");
            }
        } else if (effectsMaxPerBlock != 0
                || effectsMaxPayloadBytes != 0
                || effectsMaxExpiryBlocks != 0
                || effectsResultWindowBlocks != 0
                || effectsDefaultGate != FinalityGate.APP_FINAL
                || effectsOutcomeCommitment != EffectOutcomeCommitment.PER_EFFECT) {
            throw new IllegalArgumentException(
                    "disabled effect profile must use canonical zero limits/app-final/per-effect");
        }

        List<String> source = effectResultSigners != null ? effectResultSigners : List.of();
        if (!effectsEnabled && !source.isEmpty()) {
            throw new IllegalArgumentException("disabled effect profile cannot designate result signers");
        }
        if (source.size() > AppChainConfig.MAX_MEMBERS) {
            throw new IllegalArgumentException("effectResultSigners exceeds v1 membership bound");
        }
        Set<String> unique = new HashSet<>();
        List<String> normalized = new ArrayList<>(source.size());
        for (String signer : source) {
            String key = Objects.requireNonNull(signer, "effectResultSigner")
                    .trim().toLowerCase(Locale.ROOT);
            if (!MEMBER_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "effectResultSigner must be a 32-byte lowercase hex member key");
            }
            if (!unique.add(key)) {
                throw new IllegalArgumentException("effectResultSigners contains a duplicate key");
            }
            normalized.add(key);
        }
        normalized.sort(String::compareTo);
        effectResultSigners = List.copyOf(normalized);
    }
}
