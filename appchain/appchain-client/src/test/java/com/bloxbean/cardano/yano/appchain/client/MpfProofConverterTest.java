package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.appchain.client.MpfProofConverter;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.proofs.MpfNormalizedProof;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MpfProofConverterTest {
    @Test
    void everyRealInclusionProofNormalizesAndReconstructsTheSameRoot() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        Map<byte[], byte[]> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            byte[] key = ("claim-key-" + i).getBytes(StandardCharsets.US_ASCII);
            byte[] value = ("claim-value-" + i).getBytes(StandardCharsets.US_ASCII);
            entries.put(key, value);
            trie.put(key, value);
        }
        byte[] root = trie.getRootHash();

        for (Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
            AppChainClient.Proof wire = proof(
                    trie, root, entry.getKey(), entry.getValue());
            MpfNormalizedProof converted = MpfProofConverter.convert(wire);

            assertThat(converted.verify()).isTrue();
            assertThat(converted.stateRoot()).isEqualTo(root);
            assertThat(converted.committedHeight()).isEqualTo(42);
        }
    }

    @Test
    void conversionFailsClosedForTamperingAndNonAtomicProofs() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        byte[] key = "claim".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "value".getBytes(StandardCharsets.US_ASCII);
        trie.put(key, value);
        AppChainClient.Proof valid = proof(
                trie, trie.getRootHash(), key, value);

        AppChainClient.Proof tampered = new AppChainClient.Proof(
                valid.keyHex(),
                valid.chainId(),
                valid.stateRootHex(),
                valid.proofWireHex(),
                HexFormat.of().formatHex(
                        "other".getBytes(StandardCharsets.US_ASCII)),
                valid.finalizedAtHeight(),
                valid.committedHeight());
        AppChainClient.Proof legacy = new AppChainClient.Proof(
                valid.keyHex(),
                valid.chainId(),
                valid.stateRootHex(),
                valid.proofWireHex(),
                valid.valueHex(),
                valid.finalizedAtHeight());

        assertThatThrownBy(() -> MpfProofConverter.convert(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid MPF");
        assertThatThrownBy(() -> MpfProofConverter.convert(legacy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root-fixed");
    }

    private static AppChainClient.Proof proof(
            MpfTrie trie,
            byte[] root,
            byte[] key,
            byte[] value
    ) {
        return new AppChainClient.Proof(
                HexFormat.of().formatHex(key),
                "eutxo",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(
                        trie.getProofWire(key).orElseThrow()),
                HexFormat.of().formatHex(value),
                42L,
                42L);
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
