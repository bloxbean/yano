package org.yanoproject.api.appchain.topics;

import org.yanoproject.api.appchain.AppChainConsensusProfileCommitment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code topic-policy-v1} (ADR app-layer/039 D15): proof of the effective outcome of {@code topic} for a
 * message in block {@code height}, as key proofs against the single committed root of block
 * {@code height - 1}. It covers exactly {@link #requiredKeys(String)}: the consensus profile (whose
 * message bound is the default payload bound), the topic configuration and every applicable entry, each
 * by inclusion or absence. Verifiers resolve the outcome from these records alone (D3, D14).
 */
public record TopicPolicyProof(String topic, long height, String profile, byte[] parentRoot,
                               List<KeyProof> proofs) {
    /** One key's proof: {@code presence} is PRESENT, ABSENT or TOMBSTONED (a tombstone counts as absent). */
    public record KeyProof(byte[] key, String presence, byte[] value, byte[] proofWire) {
        public KeyProof {
            key = Objects.requireNonNull(key, "key").clone();
            Objects.requireNonNull(presence, "presence");
            value = value == null ? null : value.clone();
            proofWire = Objects.requireNonNull(proofWire, "proofWire").clone();
        }

        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value == null ? null : value.clone(); }
        @Override public byte[] proofWire() { return proofWire.clone(); }

        public boolean present() {
            return "PRESENT".equals(presence);
        }
    }

    public TopicPolicyProof {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(profile, "profile");
        parentRoot = Objects.requireNonNull(parentRoot, "parentRoot").clone();
        proofs = List.copyOf(proofs);
        if (height < 1) {
            throw new IllegalArgumentException("height must be positive");
        }
    }

    @Override
    public byte[] parentRoot() {
        return parentRoot.clone();
    }

    /** The keys a proof for {@code topic} covers, in this order. */
    public static List<byte[]> requiredKeys(String topic) {
        List<byte[]> keys = new ArrayList<>();
        keys.add(AppChainConsensusProfileCommitment.markerKey());
        keys.add(TopicKeys.config());
        for (String name : TopicNames.applicableNames(topic)) {
            keys.add(TopicKeys.entry(name));
        }
        return keys;
    }
}
