package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.appchain.client.Hex;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainStateCliTest {
    @TempDir
    Path temporary;

    private final AppChainDevtoolsCli cli = new AppChainDevtoolsCli();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void verifiesOfflineOnlyAgainstExplicitIndependentRootIdentityAndHeight() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        MpfTrie trie = new MpfTrie(store);
        byte[] key = "entry".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "value".getBytes(StandardCharsets.US_ASCII);
        trie.put(key, value);
        String root = Hex.encode(trie.getRootHash());
        Path proof = temporary.resolve("proof.json");
        Files.writeString(proof, """
                {"key":"%s","chainId":"cli-chain","stateRoot":"%s",
                 "proofWireHex":"%s","valueHex":"%s","committedHeight":1}
                """.formatted(Hex.encode(key), root,
                Hex.encode(trie.getProofWire(key).orElseThrow()), Hex.encode(value)));

        Result valid = run("state", "verify",
                "--proof-file", proof.toString(),
                "--trusted-root", root,
                "--profile", "mpf-blake2b256-v1",
                "--genesis-id", "legacy",
                "--chain", "cli-chain",
                "--height", "1",
                "--root-source", "cardano-anchor");
        Result substituted = run("state", "verify",
                "--proof-file", proof.toString(),
                "--trusted-root", "00".repeat(32),
                "--profile", "mpf-blake2b256-v1",
                "--genesis-id", "legacy",
                "--chain", "cli-chain",
                "--height", "1");
        Result missingTrust = run("state", "verify",
                "--proof-file", proof.toString(),
                "--profile", "mpf-blake2b256-v1",
                "--genesis-id", "legacy",
                "--chain", "cli-chain",
                "--height", "1");

        assertThat(valid.exit()).isZero();
        assertThat(valid.err()).isEmpty();
        JsonNode result = json.readTree(valid.out());
        assertThat(result.path("valid").asBoolean()).isTrue();
        assertThat(result.path("rootSource").asText()).isEqualTo("CARDANO_ANCHOR");
        assertThat(result.path("trustBoundary").asText())
                .isEqualTo("caller-supplied independently authenticated root");
        assertThat(substituted.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(json.readTree(substituted.out()).path("valid").asBoolean()).isFalse();
        assertThat(missingTrust.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(missingTrust.err()).contains("--trusted-root is required")
                .doesNotContain(root);
    }

    @Test
    void stateHelpDocumentsCurrentHistoricalAndTrustedRootOperations() {
        Result result = run("state", "help");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).isEmpty();
        assertThat(result.out())
                .contains("state entry|proof")
                .contains("[--height <n>]")
                .contains("state verify")
                .contains("never defaults to the root carried by the proof envelope");
    }

    private Result run(String... args) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exit = cli.run(args, new PrintWriter(output), new PrintWriter(error));
        return new Result(exit, output.toString(), error.toString());
    }

    private record Result(int exit, String out, String err) {
    }

    private static final class MemoryNodeStore implements NodeStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return values.get(Hex.encode(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            values.put(Hex.encode(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            values.remove(Hex.encode(hash));
        }
    }
}
