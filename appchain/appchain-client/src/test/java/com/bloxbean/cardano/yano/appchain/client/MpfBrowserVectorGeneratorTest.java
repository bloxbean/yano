package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the raw Java vectors consumed by the plugin browser verifier. */
class MpfBrowserVectorGeneratorTest {
    @Test
    void javaMpfProducesTheReleasedBrowserVectors() {
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            entries.put("claim-key-" + i, "claim-value-" + i);
        }
        entries.forEach((key, value) -> trie.put(bytes(key), bytes(value)));
        assertThat(hex(trie.getRootHash())).isEqualTo(
                "6f998ef5eb2e02fbef005e75a583f0496c11570db2c01512a32ddc38cff8d41e");
        assertThat(hex(trie.getProofWire(bytes("claim-key-18")).orElseThrow())).isEqualTo(
                "81d87982005880ce334a65142e8f1659f6154c4cb5a949441bd17422afd08293dcf03245fe606cdbcb1473dba748198a02c579b70b95d2219e7e45e1ba398617265d92e30f0e88b41ea223022b6c39b6211352ff9d7415de173f3a7b44c3994325be0ea9ae94b24ec8733e7b12e711b957ad2594de817bde643ff7cb0f61ab8bccefe4c5556ac8");
        assertThat(hex(trie.getProofWire(bytes("claim-key-240")).orElseThrow())).isEqualTo(
                "82d87982005880ce334a65142e8f1659f6154c4cb5a949441bd17422afd08293dcf03245fe606cc7c4105aceabcfe5f460b45d8468ce4dd0ea8f303a97bf1154ac037639a8745d19813fb7e64fe6a4e2928c86f45f6d7103b8ddc44167e019bd53c4b14fe1f9e6f475ade75f97a0efa50285d08aa72324b795fa180f6f252a8dea9f3f61faff0ed87982005880c147da10eae6ae19ecddb4c2593e13d0bf250775821bef806a44e6a107213e34789586f26046c504d0a6a3d7715ea385929e54edd49621afbdb6071029c4cba6bc0ffeeb918767330d68b9a62bb5dfe79b45588b93724f9e58fc7712a69b56450000000000000000000000000000000000000000000000000000000000000000");
        MpfTrie typed = new MpfTrie(new MapNodeStore());
        byte[] paramsKey = CompositeCommitmentV1.componentKey("l1-epoch-params-v1", bytes("params/24"));
        typed.put(paramsKey, new byte[]{1, 2});
        assertThat(hex(paramsKey)).isEqualTo(
                "79616e6f2d636f6d706f736974652d73746174652d763100126c312d65706f63682d706172616d732d76310009706172616d732f3234");
        assertThat(hex(typed.getRootHash())).isEqualTo(
                "9e0340accebd39a08017d8128b252dc97532f52db6e772f8c2e951d7609d936f");
        assertThat(hex(typed.getProofWire(paramsKey).orElseThrow())).isEqualTo("80");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();
        @Override public byte[] get(byte[] hash) { return nodes.get(hex(hash)); }
        @Override public void put(byte[] hash, byte[] nodeBytes) { nodes.put(hex(hash), nodeBytes); }
        @Override public void delete(byte[] hash) { nodes.remove(hex(hash)); }
    }
}
