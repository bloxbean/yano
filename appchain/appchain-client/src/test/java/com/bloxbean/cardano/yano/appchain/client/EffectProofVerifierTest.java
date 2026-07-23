package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EffectProofVerifierTest {

    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @Test
    void verifiesRecordPassThroughPathHistoricalStateProofAndRequestedIdentity() throws Exception {
        AppChainClient.EffectProof proof = validProof();

        assertThat(EffectProofVerifier.verify(proof)).isTrue();
        assertThat(EffectProofVerifier.verify(proof, proof.stateRootHex())).isTrue();
        assertThat(EffectProofVerifier.verifyFor(proof, "c1", 7, 2)).isTrue();
        assertThat(EffectProofVerifier.verifyFor(
                proof, proof.stateRootHex(), "c1", 7, 2)).isTrue();

        assertThat(EffectProofVerifier.verifyFor(proof, "other-chain", 7, 2)).isFalse();
        assertThat(EffectProofVerifier.verifyFor(proof, "c1", 8, 2)).isFalse();
        assertThat(EffectProofVerifier.verifyFor(proof, "c1", 7, 1)).isFalse();
        assertThat(EffectProofVerifier.verify(proof, "00".repeat(32))).isFalse();
    }

    @Test
    void verifiesEveryPositionAcrossOddAndEvenTreeWidths() throws Exception {
        for (int count = 1; count <= 12; count++) {
            for (int ordinal = 0; ordinal < count; ordinal++) {
                assertThat(EffectProofVerifier.verify(validProof(count, ordinal)))
                        .as("count=%s ordinal=%s", count, ordinal)
                        .isTrue();
            }
        }
    }

    @Test
    void failsClosedForTamperingAndMalformedPassThroughPaths() throws Exception {
        AppChainClient.EffectProof proof = validProof();

        assertThat(EffectProofVerifier.verify(copy(proof,
                mutateHex(proof.recordCborHex()), proof.effectHashHex(), proof.effectCount(),
                proof.merklePath(), proof.effectsRootHex(), proof.stateKeyHex(),
                proof.stateRootHex(), proof.stateProofWireHex(), proof.height(), proof.ordinal())))
                .isFalse();
        assertThat(EffectProofVerifier.verify(copy(proof,
                proof.recordCborHex(), "00".repeat(32), proof.effectCount(),
                proof.merklePath(), proof.effectsRootHex(), proof.stateKeyHex(),
                proof.stateRootHex(), proof.stateProofWireHex(), proof.height(), proof.ordinal())))
                .isFalse();

        List<AppChainClient.EffectMerkleStep> wrongPass = List.of(
                new AppChainClient.EffectMerkleStep(
                        AppChainClient.EffectMerkleSide.PASS_THROUGH, "11".repeat(32)),
                proof.merklePath().get(1));
        assertThat(EffectProofVerifier.verify(withPath(proof, wrongPass))).isFalse();

        List<AppChainClient.EffectMerkleStep> wrongSide = List.of(
                new AppChainClient.EffectMerkleStep(
                        AppChainClient.EffectMerkleSide.RIGHT, "11".repeat(32)),
                proof.merklePath().get(1));
        assertThat(EffectProofVerifier.verify(withPath(proof, wrongSide))).isFalse();
        assertThat(EffectProofVerifier.verify(withPath(proof,
                List.of(proof.merklePath().get(0))))).isFalse();

        assertThat(EffectProofVerifier.verify(copy(proof,
                proof.recordCborHex(), proof.effectHashHex(), 4, proof.merklePath(),
                proof.effectsRootHex(), proof.stateKeyHex(), proof.stateRootHex(),
                proof.stateProofWireHex(), proof.height(), proof.ordinal()))).isFalse();
        AppChainClient.EffectProof firstOfThree = validProof(3, 0);
        assertThat(EffectProofVerifier.verify(copy(firstOfThree,
                firstOfThree.recordCborHex(), firstOfThree.effectHashHex(), 4,
                firstOfThree.merklePath(), firstOfThree.effectsRootHex(),
                firstOfThree.stateKeyHex(), firstOfThree.stateRootHex(),
                firstOfThree.stateProofWireHex(), firstOfThree.height(),
                firstOfThree.ordinal()))).isFalse();
        assertThat(EffectProofVerifier.verify(copy(proof,
                proof.recordCborHex(), proof.effectHashHex(), proof.effectCount(), proof.merklePath(),
                "00".repeat(32), proof.stateKeyHex(), proof.stateRootHex(),
                proof.stateProofWireHex(), proof.height(), proof.ordinal()))).isFalse();
        assertThat(EffectProofVerifier.verify(copy(proof,
                proof.recordCborHex(), proof.effectHashHex(), proof.effectCount(), proof.merklePath(),
                proof.effectsRootHex(), "00", proof.stateRootHex(),
                proof.stateProofWireHex(), proof.height(), proof.ordinal()))).isFalse();
        assertThat(EffectProofVerifier.verify(copy(proof,
                proof.recordCborHex(), proof.effectHashHex(), proof.effectCount(), proof.merklePath(),
                proof.effectsRootHex(), proof.stateKeyHex(), "00".repeat(32),
                proof.stateProofWireHex(), proof.height(), proof.ordinal()))).isFalse();
        assertThat(EffectProofVerifier.verify(copy(proof,
                proof.recordCborHex(), proof.effectHashHex(), proof.effectCount(), proof.merklePath(),
                proof.effectsRootHex(), proof.stateKeyHex(), proof.stateRootHex(),
                mutateHex(proof.stateProofWireHex()), proof.height(), proof.ordinal()))).isFalse();

        // Top-level identity is also bound to the identity inside recordCbor.
        assertThat(EffectProofVerifier.verify(copy(proof,
                proof.recordCborHex(), proof.effectHashHex(), proof.effectCount(), proof.merklePath(),
                proof.effectsRootHex(), proof.stateKeyHex(), proof.stateRootHex(),
                proof.stateProofWireHex(), 8, proof.ordinal()))).isFalse();
    }

    private static AppChainClient.EffectProof validProof() throws Exception {
        return validProof(3, 2);
    }

    private static AppChainClient.EffectProof validProof(int count, int ordinal) throws Exception {
        byte[] record = effectRecordCbor("c1", 7, ordinal);
        byte[] effectHash = Blake2bUtil.blake2bHash256(record);
        List<byte[]> level = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            level.add(i == ordinal ? effectHash : Blake2bUtil.blake2bHash256(
                    ("leaf-" + count + "-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        List<AppChainClient.EffectMerkleStep> path = new ArrayList<>();
        int index = ordinal;
        while (level.size() > 1) {
            if ((index & 1) == 1) {
                path.add(new AppChainClient.EffectMerkleStep(
                        AppChainClient.EffectMerkleSide.LEFT, Hex.encode(level.get(index - 1))));
            } else if (index + 1 < level.size()) {
                path.add(new AppChainClient.EffectMerkleStep(
                        AppChainClient.EffectMerkleSide.RIGHT, Hex.encode(level.get(index + 1))));
            } else {
                path.add(new AppChainClient.EffectMerkleStep(
                        AppChainClient.EffectMerkleSide.PASS_THROUGH, ""));
            }
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i + 1 < level.size(); i += 2) {
                next.add(hashPair(level.get(i), level.get(i + 1)));
            }
            if ((level.size() & 1) == 1) {
                next.add(level.get(level.size() - 1));
            }
            level = next;
            index /= 2;
        }
        byte[] effectsRoot = bindListCount(count, level.get(0));
        byte[] stateKey = ByteBuffer.allocate("~fx/root/".length() + Long.BYTES)
                .put("~fx/root/".getBytes(StandardCharsets.UTF_8)).putLong(7).array();

        MpfTrie trie = new MpfTrie(new MapNodeStore());
        trie.put(stateKey, effectsRoot);
        trie.put("other".getBytes(StandardCharsets.UTF_8), new byte[]{1});
        byte[] stateRoot = trie.getRootHash();
        byte[] proofWire = trie.getProofWire(stateKey).orElseThrow();

        return new AppChainClient.EffectProof(
                1, "c1", 7, ordinal, Hex.encode(record), Hex.encode(effectHash), count, path,
                Hex.encode(effectsRoot), Hex.encode(stateKey), Hex.encode(stateRoot),
                Hex.encode(proofWire));
    }

    private static byte[] effectRecordCbor(String chainId, long height, int ordinal) throws Exception {
        ArrayNode record = CBOR.createArrayNode();
        record.add(1);
        record.add(chainId);
        record.add(height);
        record.add(ordinal);
        record.add("test.action");
        record.add(new byte[]{1, 2, 3});
        record.add("scope");
        record.add(0);
        record.add(1);
        record.add(100);
        record.add(new byte[0]);
        return CBOR.writeValueAsBytes(record);
    }

    private static AppChainClient.EffectProof withPath(
            AppChainClient.EffectProof proof,
            List<AppChainClient.EffectMerkleStep> path) {
        return copy(proof, proof.recordCborHex(), proof.effectHashHex(), proof.effectCount(), path,
                proof.effectsRootHex(), proof.stateKeyHex(), proof.stateRootHex(),
                proof.stateProofWireHex(), proof.height(), proof.ordinal());
    }

    private static AppChainClient.EffectProof copy(
            AppChainClient.EffectProof proof,
            String recordCborHex,
            String effectHashHex,
            int effectCount,
            List<AppChainClient.EffectMerkleStep> path,
            String effectsRootHex,
            String stateKeyHex,
            String stateRootHex,
            String stateProofWireHex,
            long height,
            int ordinal) {
        return new AppChainClient.EffectProof(proof.version(), proof.chainId(), height, ordinal,
                recordCborHex, effectHashHex, effectCount, path, effectsRootHex, stateKeyHex,
                stateRootHex, stateProofWireHex);
    }

    private static String mutateHex(String hex) {
        char last = hex.charAt(hex.length() - 1);
        return hex.substring(0, hex.length() - 1) + (last == '0' ? '1' : '0');
    }

    private static byte[] hashPair(byte[] left, byte[] right) {
        byte[] pair = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, pair, left.length, right.length);
        return Blake2bUtil.blake2bHash256(pair);
    }

    private static byte[] bindListCount(int count, byte[] rawRoot) {
        byte[] domain = "yano:fx:list-root:v1".getBytes(StandardCharsets.UTF_8);
        return Blake2bUtil.blake2bHash256(ByteBuffer.allocate(
                        domain.length + Integer.BYTES + rawRoot.length)
                .put(domain).putInt(count).put(rawRoot).array());
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(Hex.encode(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(Hex.encode(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(Hex.encode(hash));
        }
    }
}
