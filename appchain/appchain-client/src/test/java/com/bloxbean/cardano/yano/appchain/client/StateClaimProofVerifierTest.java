package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofLabVocabulary;
import com.bloxbean.cardano.yano.api.appchain.proof.ProofSubjectProvider;
import com.bloxbean.cardano.yano.api.appchain.proof.StateClaimProofPackageV1;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.state.StateProof;
import com.bloxbean.cardano.yano.api.appchain.state.StateProofEnvelope;
import com.bloxbean.cardano.yano.api.appchain.state.StateSnapshot;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessageRootIndex;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessagesProofSubjectProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StateClaimProofVerifierTest {
    @Test
    void recomputesProofFactAndClaimAndIgnoresEmbeddedVerdict() {
        byte[] key = FinalizedBlockMessageRootIndex.blockKey(7);
        byte[] value = new FinalizedBlockMessageRootIndex.BlockRecord(
                7, filled(4), 3).canonicalBytes();
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        trie.put(key, value);
        byte[] root = trie.getRootHash();
        StateCommitmentIdentity identity = StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF, filled(2));
        StateProof proof = new StateProof(new StateSnapshot(identity, 8, root), key, value,
                StateProof.Presence.PRESENT, StateCommitmentProfiles.MPF.proofEncodingId(),
                trie.getProofWire(key).orElseThrow());
        StateProofEnvelope envelope = new StateProofEnvelope(1, "proof-chain", filled(5),
                proof, FinalityCert.empty());
        var provider = new FinalizedBlockMessagesProofSubjectProvider();
        var claim = new ProofSubjectProvider.ClaimRequest(
                "count-equals", Map.of("expected", "3"));
        var bundle = new StateClaimProofPackageV1(StateClaimProofPackageV1.SCHEMA,
                FinalizedBlockMessagesProofSubjectProvider.DESCRIPTOR, Map.of("height", "7"),
                claim, value, envelope, null, null,
                Map.of("accepted", false, "actual", "tampered presentation"));
        var trust = new StateClaimProofVerifier.TrustContext("proof-chain", identity, 8, root,
                ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT);

        assertThat(StateClaimProofVerifier.verify(bundle, provider, trust).accepted()).isTrue();
        var falseClaim = new StateClaimProofPackageV1(StateClaimProofPackageV1.SCHEMA,
                bundle.descriptor(), bundle.normalizedCoordinates(),
                new ProofSubjectProvider.ClaimRequest("count-equals", Map.of("expected", "4")),
                value, envelope, null, null, Map.of("accepted", true));
        assertThat(StateClaimProofVerifier.verify(falseClaim, provider, trust).accepted()).isFalse();
        var falseFact = new StateClaimProofPackageV1(StateClaimProofPackageV1.SCHEMA,
                bundle.descriptor(), bundle.normalizedCoordinates(), claim,
                Arrays.copyOf(value, value.length - 1), envelope, null, null, Map.of());
        assertThat(StateClaimProofVerifier.verify(falseFact, provider, trust).accepted()).isFalse();
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();
        @Override public byte[] get(byte[] hash) { return nodes.get(HexFormat.of().formatHex(hash)); }
        @Override public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }
        @Override public void delete(byte[] hash) { nodes.remove(HexFormat.of().formatHex(hash)); }
    }
}
