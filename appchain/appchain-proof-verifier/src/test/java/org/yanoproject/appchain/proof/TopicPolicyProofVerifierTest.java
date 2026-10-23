package org.yanoproject.appchain.proof;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainConsensusProfileCommitment;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicPolicyProof;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EffectiveOutcome;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.OutcomeKind;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicWireCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR app-layer/039 D15 / I16 / V3: topic-policy-v1 proofs verified offline against real MPF proofs. */
class TopicPolicyProofVerifierTest {
    private static final String TOPIC = "acme/orders/new";
    private static final KeySet OWNERS = new KeySet(1, List.of("11".repeat(32)));
    private static final KeySet WRITERS = new KeySet(1, List.of("22".repeat(32)));

    @Test
    void provesThePolicyOfARegisteredTopic() {
        Trie trie = new Trie().profile().config(true);
        trie.entry("acme/", new Policy(new Writers(WRITERS), null, null, null, null));
        TopicPolicyProof proof = trie.proof(TOPIC);

        EffectiveOutcome outcome = TopicPolicyProofVerifier.verify(proof, trie.root()).orElseThrow();
        assertThat(outcome.kind()).isEqualTo(OutcomeKind.POLICY);
        assertThat(outcome.policy().writers()).isEqualTo(new Writers(WRITERS));
        assertThat(outcome.sources()).extracting(source -> source.name()).containsExactly("acme/");
        assertThat(TopicPolicyProofVerifier.verifyDigest(proof, trie.root(), TopicWireCodec.outcomeDigest(outcome)))
                .isTrue();
    }

    @Test
    void theConfigurationDecidesAnUnregisteredTopic() {
        Trie reject = new Trie().profile().config(true);
        Trie open = new Trie().profile().config(false);
        assertThat(TopicPolicyProofVerifier.verify(reject.proof(TOPIC), reject.root()).orElseThrow().kind())
                .isEqualTo(OutcomeKind.UNREGISTERED_REJECT);
        assertThat(TopicPolicyProofVerifier.verify(open.proof(TOPIC), open.root()).orElseThrow().kind())
                .isEqualTo(OutcomeKind.UNREGISTERED_OPEN);
        Trie disabled = new Trie().profile();
        assertThat(TopicPolicyProofVerifier.verify(disabled.proof(TOPIC), disabled.root()).orElseThrow().kind())
                .isEqualTo(OutcomeKind.DISABLED);
    }

    @Test
    void incompletePaddedForeignOrTamperedProofsFailClosed() {
        Trie trie = new Trie().profile().config(true);
        trie.entry("acme/", Policy.INHERIT);
        trie.entry("acme/orders/", new Policy(Writers.OPEN, null, null, null, null));
        TopicPolicyProof proof = trie.proof(TOPIC);
        assertThat(TopicPolicyProofVerifier.verify(proof, trie.root())).isPresent();

        List<TopicPolicyProof.KeyProof> withoutOverride = new ArrayList<>(proof.proofs());
        withoutOverride.removeIf(keyProof -> java.util.Arrays.equals(keyProof.key(), TopicKeys.entry("acme/orders/")));
        assertThat(TopicPolicyProofVerifier.verify(new TopicPolicyProof(TOPIC, proof.height(), proof.profile(),
                proof.parentRoot(), withoutOverride), trie.root())).as("omitted override").isEmpty();

        List<TopicPolicyProof.KeyProof> padded = new ArrayList<>(proof.proofs());
        padded.add(proof.proofs().getFirst());
        assertThat(TopicPolicyProofVerifier.verify(new TopicPolicyProof(TOPIC, proof.height(), proof.profile(),
                proof.parentRoot(), padded), trie.root())).as("duplicate key").isEmpty();

        assertThat(TopicPolicyProofVerifier.verify(proof, new byte[32])).as("another root").isEmpty();

        List<TopicPolicyProof.KeyProof> tampered = new ArrayList<>();
        for (TopicPolicyProof.KeyProof keyProof : proof.proofs()) {
            boolean config = java.util.Arrays.equals(keyProof.key(), TopicKeys.config());
            tampered.add(config ? new TopicPolicyProof.KeyProof(keyProof.key(), keyProof.presence(),
                    TopicWireCodec.encodeConfig(new Config(1, 1, settings(false))), keyProof.proofWire()) : keyProof);
        }
        assertThat(TopicPolicyProofVerifier.verify(new TopicPolicyProof(TOPIC, proof.height(), proof.profile(),
                proof.parentRoot(), tampered), trie.root())).as("tampered configuration").isEmpty();
    }

    @Test
    void aPendingOnlyCreationCountsAsAbsent() {
        Trie trie = new Trie().profile().config(true);
        trie.trie.put(TopicKeys.entry("acme/"), TopicWireCodec.encodeEntryRecord(new EntryRecord("acme/", null, 1, 1,
                0)));
        assertThat(TopicPolicyProofVerifier.verify(trie.proof(TOPIC), trie.root()).orElseThrow().kind())
                .isEqualTo(OutcomeKind.UNREGISTERED_REJECT);
    }

    @Test
    void keyProofsFromTwoRootsFailClosed() {
        Trie trie = new Trie().profile().config(true);
        TopicPolicyProof before = trie.proof(TOPIC);
        trie.entry("acme/", new Policy(new Writers(WRITERS), null, null, null, null));
        TopicPolicyProof after = trie.proof(TOPIC);

        // the acme/ entry proven at the later root, every other key at the earlier one
        List<TopicPolicyProof.KeyProof> mixed = new ArrayList<>(before.proofs());
        for (int i = 0; i < mixed.size(); i++) {
            if (java.util.Arrays.equals(mixed.get(i).key(), TopicKeys.entry("acme/"))) {
                mixed.set(i, after.proofs().get(i));
            }
        }
        for (byte[] root : List.of(before.parentRoot(), after.parentRoot())) {
            assertThat(TopicPolicyProofVerifier.verify(new TopicPolicyProof(TOPIC, before.height(), before.profile(),
                    root, mixed), root)).isEmpty();
        }
    }

    private static Settings settings(boolean reject) {
        return new Settings(true, null, false, reject, 3_600_000, 3_600_000, 4, 100, 10, 2, 600);
    }

    /** An in-memory MPF state holding just the keys a topic-policy proof needs. */
    private static final class Trie {
        private final Map<String, byte[]> nodes = new HashMap<>();
        private final MpfTrie trie = new MpfTrie(new NodeStore() {
            @Override public byte[] get(byte[] hash) { return nodes.get(HexFormat.of().formatHex(hash)); }
            @Override public void put(byte[] hash, byte[] node) { nodes.put(HexFormat.of().formatHex(hash), node); }
            @Override public void delete(byte[] hash) { nodes.remove(HexFormat.of().formatHex(hash)); }
        });

        Trie profile() {
            trie.put(AppChainConsensusProfileCommitment.markerKey(), AppChainConsensusProfileCommitment.encode(
                    new AppChainConsensusProfile(AppChainConsensusProfile.SCHEMA_VERSION, 65_536, 64, 4_194_304,
                            0, 0, 5_000, false, false, 0, 0, 0, 0, FinalityGate.APP_FINAL,
                            EffectOutcomeCommitment.PER_EFFECT, true, List.of())));
            return this;
        }

        Trie config(boolean reject) {
            trie.put(TopicKeys.config(), TopicWireCodec.encodeConfig(new Config(1, 1, settings(reject))));
            return this;
        }

        void entry(String name, Policy policy) {
            trie.put(TopicKeys.entry(name), TopicWireCodec.encodeEntryRecord(new EntryRecord(name,
                    new ActiveRevision(1, 2, 1, new EntryContent(OWNERS, policy, false)), 1, 0, 0)));
        }

        byte[] root() {
            return trie.getRootHash();
        }

        TopicPolicyProof proof(String topic) {
            List<TopicPolicyProof.KeyProof> proofs = new ArrayList<>();
            for (byte[] key : TopicPolicyProof.requiredKeys(topic)) {
                byte[] value = trie.get(key);
                byte[] wire = trie.getProofWire(key).orElseThrow();
                proofs.add(new TopicPolicyProof.KeyProof(key, value != null ? "PRESENT" : "ABSENT", value, wire));
            }
            return new TopicPolicyProof(topic, 3, AppChainProofVerifier.MPF_BLAKE2B256_V1, root(), proofs);
        }
    }
}
