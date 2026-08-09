package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.appchain.client.Hex;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
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
import java.util.List;
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
        ProofVerifier.ProfileMetadata profile = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        String genesis = "11".repeat(32);
        String blockHash = "22".repeat(32);
        Path proof = temporary.resolve("proof.json");
        Files.writeString(proof, """
                {"key":"%s","chainId":"cli-chain","stateRoot":"%s",
                 "proofWireHex":"%s","valueHex":"%s","committedHeight":1,
                 "proofSchemaVersion":1,"schemaVersion":1,"profile":"%s",
                 "backend":"%s","commitmentFormatId":"%s",
                 "formatFingerprint":"%s","genesisId":"%s",
                 "proofEncodingId":"%s","nativeVersioning":%s,
                 "physicalDelete":%s,"version":1,"oldestProvableHeight":1,
                 "presence":"PRESENT","blockHash":"%s",
                 "block":{"version":1,"height":1,"prevHash":"%s","l1Slot":0,
                   "l1BlockHash":"","timestamp":1,"messagesRoot":"%s",
                   "stateRoot":"%s","blockHash":"%s"},
                 "finalityCertificate":{"scheme":0,"signatures":[
                   {"signer":"%s","signature":"%s"}]}}
                """.formatted(Hex.encode(key), root,
                Hex.encode(trie.getProofWire(key).orElseThrow()), Hex.encode(value),
                profile.id(), profile.backend(), profile.commitmentFormatId(),
                profile.formatFingerprintHex(), genesis, profile.proofEncodingId(),
                profile.nativeVersioning(), profile.physicalDelete(), blockHash,
                "00".repeat(32), "33".repeat(32), root, blockHash,
                "44".repeat(32), "55".repeat(64)));

        Result valid = run("state", "verify",
                "--proof-file", proof.toString(),
                "--trusted-root", root,
                "--profile", "mpf-blake2b256-v1",
                "--genesis-id", genesis,
                "--chain", "cli-chain",
                "--height", "1",
                "--block-hash", blockHash,
                "--root-source", "cardano-anchor");
        Result substituted = run("state", "verify",
                "--proof-file", proof.toString(),
                "--trusted-root", "00".repeat(32),
                "--profile", "mpf-blake2b256-v1",
                "--genesis-id", genesis,
                "--chain", "cli-chain",
                "--height", "1");
        Result missingTrust = run("state", "verify",
                "--proof-file", proof.toString(),
                "--profile", "mpf-blake2b256-v1",
                "--genesis-id", genesis,
                "--chain", "cli-chain",
                "--height", "1");

        assertThat(valid.exit()).as(valid.err()).isZero();
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
                .contains("--block-hash <64-hex>")
                .contains("state validate")
                .contains("state validators")
                .contains("state explain")
                .contains("authoritative validation occurs during apply")
                .contains("never defaults to the root carried by the proof envelope");
    }

    @Test
    void validatesSchemaCandidatesAndInspectsConsensusValidatorDigestsOffline()
            throws Exception {
        Path genesis = writeGenesis();

        Result valid = run("state", "validate",
                "--genesis-file", genesis.toString(),
                "--collection", "records", "--key", "01",
                "--value-hex", "a16371747905");
        Result invalid = run("state", "validate",
                "--genesis-file", genesis.toString(),
                "--collection", "records", "--key", "01",
                "--value-hex", "a1637174790b");
        Result pluginUnavailable = run("state", "validate",
                "--genesis-file", genesis.toString(),
                "--collection", "identifiers", "--key", "01",
                "--value-hex", "07");
        Result validators = run("state", "validators",
                "--genesis-file", genesis.toString());
        Result explanation = run("state", "explain", "--code", "11");

        assertThat(valid.exit()).isZero();
        JsonNode validResult = json.readTree(valid.out());
        assertThat(validResult.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(validResult.path("authoritative").asBoolean()).isFalse();
        assertThat(validResult.path("trustBoundary").asText()).contains("advisory");
        assertThat(invalid.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(json.readTree(invalid.out()).path("codeName").asText())
                .isEqualTo("VALUE_SCHEMA");
        assertThat(pluginUnavailable.exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(json.readTree(pluginUnavailable.out()).path("status").asText())
                .isEqualTo("UNAVAILABLE");
        JsonNode validatorSet = json.readTree(validators.out());
        assertThat(validatorSet.path("consensusBound").asBoolean()).isTrue();
        assertThat(validatorSet.path("validators").size()).isEqualTo(2);
        assertThat(validatorSet.toString())
                .contains("definitionSha256", "artifactClosureSha256", "parametersSha256");
        assertThat(json.readTree(explanation.out()).path("name").asText())
                .isEqualTo("VALUE_SCHEMA");
    }

    private Path writeGenesis() throws Exception {
        byte[] schema = AuthenticatedMapCddlCompiler.compile(
                "root = { qty: uint .le 10 }").definition();
        AuthenticatedMapContract.ValidatorDescriptor recordValidator =
                AuthenticatedMapContract.ValidatorDescriptor.schema("record-v1", schema);
        AuthenticatedMapContract.ValidatorDescriptor pluginValidator =
                AuthenticatedMapContract.ValidatorDescriptor.plugin(
                        "identifier-v1", "test-provider", new byte[32],
                        new byte[]{(byte) 0xa0});
        AuthenticatedMapContract.Genesis value = new AuthenticatedMapContract.Genesis(
                "cli-chain", AuthenticatedMapContract.PROFILE_MPF_BLAKE2B256_V1,
                new byte[32], new byte[32], new byte[32], new byte[32],
                16, 65_536,
                List.of(
                        new AuthenticatedMapContract.CollectionDescriptor(
                                "records", AuthenticatedMapContract.AUTH_OPEN, false,
                                64, 1024,
                                AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
                                recordValidator.id()),
                        new AuthenticatedMapContract.CollectionDescriptor(
                                "identifiers", AuthenticatedMapContract.AUTH_OPEN, false,
                                64, 1024, AuthenticatedMapContract.VALUE_ENCODING_OPAQUE,
                                pluginValidator.id())),
                List.of(recordValidator, pluginValidator),
                List.of());
        Path file = temporary.resolve("authenticated-map-genesis.hex");
        Files.writeString(file, Hex.encode(AuthenticatedMapContract.encodeGenesis(value)) + "\n");
        return file;
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
