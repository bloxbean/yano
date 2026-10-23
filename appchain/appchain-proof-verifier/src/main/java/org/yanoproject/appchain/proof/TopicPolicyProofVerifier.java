package org.yanoproject.appchain.proof;

import org.yanoproject.api.appchain.AppChainConsensusProfileCommitment;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicNames;
import org.yanoproject.api.appchain.topics.TopicPolicyProof;
import org.yanoproject.api.appchain.topics.TopicPolicyResolver;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWireCodec;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Offline verification of {@code topic-policy-v1} (ADR app-layer/039 D15, I16): every required key is
 * proven once, by inclusion or absence, against one independently trusted root; the outcome is then
 * resolved from the proven records alone, without registrar configuration, epochs or activation order.
 * The caller binds the root: {@code trustedParentRoot} must be the state root it trusts for height
 * {@code h-1} (from a verified finality certificate), since the proof governs writes at height {@code h}.
 */
public final class TopicPolicyProofVerifier {
    private TopicPolicyProofVerifier() {
    }

    /** The proven effective outcome, or empty when the proof is incomplete, padded or does not verify. */
    public static Optional<EffectiveOutcome> verify(TopicPolicyProof proof, byte[] trustedParentRoot) {
        if (proof == null || trustedParentRoot == null || !Arrays.equals(proof.parentRoot(), trustedParentRoot)) {
            return Optional.empty();
        }
        List<byte[]> required = TopicPolicyProof.requiredKeys(proof.topic());
        if (proof.proofs().size() != required.size()) {
            return Optional.empty();
        }
        Map<String, TopicPolicyProof.KeyProof> byKey = new HashMap<>();
        for (TopicPolicyProof.KeyProof keyProof : proof.proofs()) {
            AppChainProofVerifier.Presence presence;
            try {
                presence = AppChainProofVerifier.Presence.valueOf(keyProof.presence());
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
            if (!AppChainProofVerifier.verify(proof.profile(), presence, trustedParentRoot, keyProof.key(),
                    keyProof.value(), keyProof.proofWire())
                    || byKey.put(Arrays.toString(keyProof.key()), keyProof) != null) {
                return Optional.empty();
            }
        }
        for (byte[] key : required) {
            if (!byKey.containsKey(Arrays.toString(key))) {
                return Optional.empty(); // an omitted override, ancestor or configuration fails closed
            }
        }
        try {
            byte[] profile = present(byKey, AppChainConsensusProfileCommitment.markerKey());
            if (profile == null) {
                return Optional.empty();
            }
            long maxMessageBytes = AppChainConsensusProfileCommitment.decode(profile).maxMessageBytes();
            byte[] configBytes = present(byKey, TopicKeys.config());
            Config config = configBytes == null ? null : TopicWireCodec.decodeConfig(configBytes);
            Map<String, EntryRecord> entries = new HashMap<>();
            for (String name : TopicNames.applicableNames(proof.topic())) {
                byte[] record = present(byKey, TopicKeys.entry(name));
                if (record != null) {
                    EntryRecord entry = TopicWireCodec.decodeEntryRecord(record);
                    if (!entry.name().equals(name)) {
                        return Optional.empty();
                    }
                    entries.put(name, entry);
                }
            }
            return Optional.of(TopicPolicyResolver.resolve(proof.topic(), config, maxMessageBytes, entries));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** True if the proof verifies and its outcome has {@code effectivePolicyDigest}. */
    public static boolean verifyDigest(TopicPolicyProof proof, byte[] trustedParentRoot, String effectivePolicyDigest) {
        return verify(proof, trustedParentRoot)
                .map(outcome -> TopicWireCodec.outcomeDigest(outcome).equals(effectivePolicyDigest))
                .orElse(false);
    }

    private static byte[] present(Map<String, TopicPolicyProof.KeyProof> byKey, byte[] key) {
        TopicPolicyProof.KeyProof proof = byKey.get(Arrays.toString(key));
        return proof != null && proof.present() ? proof.value() : null;
    }
}
