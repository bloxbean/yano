package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.AuthenticatedMapPreflight;
import com.bloxbean.cardano.yano.appchain.client.Hex;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** ADR-025 online state inspection and offline trusted-root proof verification. */
final class AppChainStateCli {
    private static final int MAX_PROOF_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_GENESIS_FILE_BYTES = 16 * 1024 * 1024;
    private static final Set<String> COMMANDS = Set.of(
            "entry", "proof", "verify", "identity", "integrity", "snapshot", "oldest",
            "validate", "validators", "explain");
    static final String USAGE = """
            Usage: ./yano.sh appchain state entry|proof --url <api-base> --chain <id>
                     --key <hex> [--height <n>] [--api-key <key>]
               or: ./yano.sh appchain state verify --proof-file <json>
                     --trusted-root <hex> --profile <id> --genesis-id <64-hex>
                     --chain <id> --height <n> [--root-source <source>]
               or: ./yano.sh appchain state identity|integrity|oldest --url <api-base> --chain <id> [--api-key <key>]
               or: ./yano.sh appchain state snapshot --url <api-base> --chain <id>
                     --path <server-path> [--api-key <key>]
               or: ./yano.sh appchain state validate --genesis-file <cbor-or-hex-file>
                     --collection <id> --key <lower-hex>
                     (--value-hex <lower-hex> | --value-file <path>)
               or: ./yano.sh appchain state validators --genesis-file <cbor-or-hex-file>
               or: ./yano.sh appchain state explain --code <0..12>

            Trusted root sources: locally-verified-block, finality-certificate, cardano-anchor, caller-pinned
            Verification never defaults to the root carried by the proof envelope.
            Candidate validation is advisory; authoritative validation occurs during apply.
            """.stripTrailing();

    private final ObjectMapper json = new ObjectMapper();

    int run(String[] args, PrintWriter out, PrintWriter err) {
        try {
            if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
                out.println(USAGE);
                out.flush();
                return AppChainDevtoolsCli.EXIT_OK;
            }
            String command = args[0];
            if (!COMMANDS.contains(command)) throw new Usage("Unknown state command: " + command);
            Map<String, String> options = parseOptions(args);
            int result = switch (command) {
                case "entry" -> lookup(options, false, out);
                case "proof" -> lookup(options, true, out);
                case "verify" -> verify(options, out);
                case "identity" -> identity(options, out);
                case "integrity" -> integrity(options, out);
                case "snapshot" -> snapshot(options, out);
                case "oldest" -> oldest(options, out);
                case "validate" -> validateCandidate(options, out);
                case "validators" -> validators(options, out);
                case "explain" -> explain(options, out);
                default -> throw new Usage("Unknown state command: " + command);
            };
            out.flush();
            return result;
        } catch (Usage invalid) {
            err.println(invalid.getMessage());
            err.println(USAGE);
            err.flush();
            return AppChainDevtoolsCli.EXIT_USAGE;
        } catch (IOException failure) {
            err.println("State input file could not be read");
            err.flush();
            return AppChainDevtoolsCli.EXIT_IO;
        } catch (AppChainClient.AppChainClientException failure) {
            err.println("App-chain state request failed: " + firstLine(failure.getMessage()));
            err.flush();
            return AppChainDevtoolsCli.EXIT_IO;
        } catch (RuntimeException failure) {
            err.println("App-chain state command failed: " + firstLine(failure.getMessage()));
            err.flush();
            return AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
        }
    }

    private int lookup(Map<String, String> options, boolean includeProof, PrintWriter out)
            throws IOException {
        rejectUnknown(options, Set.of("url", "chain", "api-key", "key", "height"));
        String keyHex = required(options, "key");
        if (!keyHex.matches("(?:[0-9a-f]{2}){1,256}")) {
            throw new Usage("--key must contain 1-256 bytes of canonical lowercase hex");
        }
        Long height = optionalPositiveLong(options, "height");
        AppChainClient client = client(options);
        if (!includeProof) {
            JsonNode entry = (height == null
                    ? client.stateEntry(Hex.decode(keyHex))
                    : client.stateEntry(Hex.decode(keyHex), height)).orElseThrow(() ->
                    new AppChainClient.AppChainClientException(
                            "No retained state entry is available for the requested key/version"));
            print(entry, out);
            return AppChainDevtoolsCli.EXIT_OK;
        }
        AppChainClient.Proof proof = (height == null
                ? client.proof(Hex.decode(keyHex))
                : client.proof(Hex.decode(keyHex), height)).orElseThrow(() ->
                new AppChainClient.AppChainClientException(
                        "No retained state proof is available for the requested key/version"));
        print(proofJson(proof, includeProof), out);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int verify(Map<String, String> options, PrintWriter out) throws IOException {
        rejectUnknown(options, Set.of("proof-file", "trusted-root", "profile", "genesis-id",
                "chain", "height", "root-source"));
        Path proofFile = Path.of(required(options, "proof-file"));
        long size = Files.size(proofFile);
        if (size <= 0 || size > MAX_PROOF_FILE_BYTES) {
            throw new Usage("--proof-file must contain at most 5 MiB");
        }
        String proofJson = Files.readString(proofFile, StandardCharsets.UTF_8);
        AppChainClient.Proof proof = AppChainClient.decodeProofEnvelope(proofJson);
        String genesis = required(options, "genesis-id");
        ProofVerifier.TrustedRootSource source = parseRootSource(
                options.getOrDefault("root-source", "caller-pinned"));
        ProofVerifier.TrustedStateRoot trusted = new ProofVerifier.TrustedStateRoot(
                required(options, "chain"), required(options, "profile"), genesis,
                requiredPositiveLong(options, "height"), required(options, "trusted-root"), source);
        boolean valid = ProofVerifier.verify(proof, trusted);
        ObjectNode result = json.createObjectNode();
        result.put("valid", valid);
        result.put("chainId", trusted.chainId());
        result.put("profile", trusted.profile());
        result.put("genesisId", trusted.genesisIdHex());
        result.put("height", trusted.height());
        result.put("stateRoot", trusted.stateRootHex());
        result.put("rootSource", trusted.source().name());
        result.put("trustBoundary", "caller-supplied independently authenticated root");
        print(result, out);
        return valid ? AppChainDevtoolsCli.EXIT_OK : AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
    }

    private int integrity(Map<String, String> options, PrintWriter out) throws IOException {
        rejectUnknown(options, Set.of("url", "chain", "api-key"));
        JsonNode result = client(options).stateIntegrity();
        print(result, out);
        return result.path("valid").asBoolean(false)
                ? AppChainDevtoolsCli.EXIT_OK : AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
    }

    private int identity(Map<String, String> options, PrintWriter out) throws IOException {
        rejectUnknown(options, Set.of("url", "chain", "api-key"));
        print(client(options).stateIdentity(), out);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int snapshot(Map<String, String> options, PrintWriter out) throws IOException {
        rejectUnknown(options, Set.of("url", "chain", "api-key", "path"));
        print(client(options).snapshot(required(options, "path")), out);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int oldest(Map<String, String> options, PrintWriter out) throws IOException {
        rejectUnknown(options, Set.of("url", "chain", "api-key"));
        AppChainClient client = client(options);
        JsonNode identity = client.stateIdentity();
        ObjectNode result = json.createObjectNode();
        result.put("chainId", required(options, "chain"));
        result.put("profile", identity.path("profile").asText());
        result.put("backend", identity.path("backend").asText());
        result.put("oldestProvableHeight", client.oldestProvableHeight());
        print(result, out);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int validateCandidate(Map<String, String> options, PrintWriter out)
            throws IOException {
        rejectUnknown(options, Set.of(
                "genesis-file", "collection", "key", "value-hex", "value-file"));
        AuthenticatedMapContract.Genesis genesis = readGenesis(
                Path.of(required(options, "genesis-file")));
        String keyHex = required(options, "key");
        if (!keyHex.matches("(?:[0-9a-f]{2}){1,128}")) {
            throw new Usage("--key must contain 1-128 bytes of canonical lowercase hex");
        }
        byte[] value = candidateValue(options);
        AuthenticatedMapPreflight.Result check = AuthenticatedMapPreflight
                .fromGenesis(genesis)
                .validate(required(options, "collection"), Hex.decode(keyHex), value);
        ObjectNode result = json.createObjectNode();
        result.put("status", check.status().name());
        result.put("code", check.code());
        result.put("codeName", check.codeName());
        result.put("mechanism", check.mechanism());
        result.put("detail", check.detail());
        result.put("authoritative", false);
        result.put("chainId", genesis.chainId());
        result.put("genesisId", Hex.encode(AuthenticatedMapContract.genesisId(genesis)));
        result.put("collectionId", required(options, "collection"));
        result.put("trustBoundary",
                "offline advisory preflight against the supplied canonical genesis");
        print(result, out);
        return check.accepted()
                ? AppChainDevtoolsCli.EXIT_OK : AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
    }

    private int validators(Map<String, String> options, PrintWriter out) throws IOException {
        rejectUnknown(options, Set.of("genesis-file"));
        AuthenticatedMapContract.Genesis genesis = readGenesis(
                Path.of(required(options, "genesis-file")));
        ObjectNode result = json.createObjectNode();
        result.put("chainId", genesis.chainId());
        result.put("profile", genesis.commitmentProfileId());
        result.put("formatFingerprint", Hex.encode(genesis.formatFingerprint()));
        result.put("genesisId", Hex.encode(AuthenticatedMapContract.genesisId(genesis)));
        ArrayNode collections = result.putArray("collections");
        for (AuthenticatedMapContract.CollectionDescriptor descriptor : genesis.collections()) {
            ObjectNode item = collections.addObject();
            item.put("id", descriptor.id());
            item.put("valueEncoding", encodingName(descriptor.valueEncoding()));
            item.put("validatorId", descriptor.validatorId());
            item.put("maxKeyBytes", descriptor.maxKeyBytes());
            item.put("maxValueBytes", descriptor.maxValueBytes());
        }
        ArrayNode validators = result.putArray("validators");
        for (AuthenticatedMapContract.ValidatorDescriptor descriptor : genesis.validators()) {
            ObjectNode item = validators.addObject();
            item.put("id", descriptor.id());
            item.put("kind", descriptor.kind() == AuthenticatedMapContract.VALIDATOR_KIND_SCHEMA
                    ? "schema" : "plugin");
            item.put("providerId", descriptor.providerId());
            item.put("contractVersion", descriptor.contractVersion());
            if (descriptor.kind() == AuthenticatedMapContract.VALIDATOR_KIND_PLUGIN) {
                item.put("artifactClosureSha256", Hex.encode(descriptor.definition()));
            } else {
                item.put("definitionSha256", sha256(descriptor.definition()));
            }
            item.put("parametersSha256", sha256(descriptor.parameters()));
        }
        result.put("validatorCount", genesis.validators().size());
        result.put("consensusBound", true);
        print(result, out);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int explain(Map<String, String> options, PrintWriter out) throws IOException {
        rejectUnknown(options, Set.of("code"));
        int code;
        try {
            code = Integer.parseInt(required(options, "code"));
        } catch (NumberFormatException invalid) {
            throw new Usage("--code must be an integer in [0, 12]");
        }
        AuthenticatedMapPreflight.Explanation explanation;
        try {
            explanation = AuthenticatedMapPreflight.explain(code);
        } catch (IllegalArgumentException invalid) {
            throw new Usage("--code must be an integer in [0, 12]");
        }
        ObjectNode result = json.createObjectNode();
        result.put("code", explanation.code());
        result.put("name", explanation.name());
        result.put("mechanism", explanation.mechanism());
        result.put("meaning", explanation.meaning());
        result.put("receiptAuthenticatedOnlyAfterApply", true);
        print(result, out);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private static AuthenticatedMapContract.Genesis readGenesis(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new Usage("--genesis-file must name a regular non-symlink file");
        }
        long size = Files.size(path);
        if (size <= 0 || size > MAX_GENESIS_FILE_BYTES) {
            throw new Usage("--genesis-file must contain at most 16 MiB");
        }
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        byte[] canonical = text.matches("[0-9a-f]+") && (text.length() & 1) == 0
                ? Hex.decode(text) : bytes;
        return AuthenticatedMapContract.decodeGenesis(canonical);
    }

    private static byte[] candidateValue(Map<String, String> options) throws IOException {
        String valueHex = options.get("value-hex");
        String valueFile = options.get("value-file");
        if ((valueHex == null) == (valueFile == null)) {
            throw new Usage("exactly one of --value-hex or --value-file is required");
        }
        if (valueHex != null) {
            if ((valueHex.length() & 1) != 0 || valueHex.length() > 2 * 1_048_576
                    || !valueHex.matches("[0-9a-f]*")) {
                throw new Usage("--value-hex must be at most 1 MiB of canonical lowercase hex");
            }
            return Hex.decode(valueHex);
        }
        Path path = Path.of(valueFile);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path) || Files.size(path) > 1_048_576) {
            throw new Usage("--value-file must be a regular non-symlink file of at most 1 MiB");
        }
        return Files.readAllBytes(path);
    }

    private static String encodingName(int encoding) {
        return encoding == AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR
                ? "canonical-cbor" : "opaque";
    }

    private static String sha256(byte[] value) {
        try {
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private AppChainClient client(Map<String, String> options) {
        String url = required(options, "url");
        String chain = required(options, "chain");
        AppChainClient.Builder builder = AppChainClient.builder(url).chainId(chain);
        String apiKey = options.get("api-key");
        if (apiKey != null) builder.apiKey(apiKey);
        return builder.build();
    }

    private ObjectNode proofJson(AppChainClient.Proof proof, boolean includeProof) {
        ObjectNode node = json.createObjectNode();
        node.put("key", proof.keyHex());
        node.put("chainId", proof.chainId());
        node.put("stateRoot", proof.stateRootHex());
        node.put("committedHeight", proof.committedHeight());
        if (includeProof) node.put("proofWireHex", proof.proofWireHex());
        if (proof.valueHex() != null) node.put("valueHex", proof.valueHex());
        if (proof.finalizedAtHeight() != null) {
            node.put("finalizedAtHeight", proof.finalizedAtHeight());
        }
        node.put("presence", proof.presence().name());
        if (proof.proofSchemaVersion() != null && proof.proofSchemaVersion() == 1) {
            node.put("proofSchemaVersion", 1);
            node.put("schemaVersion", 1);
            node.put("profile", proof.profile());
            node.put("backend", proof.backend());
            node.put("commitmentFormatId", proof.commitmentFormatId());
            node.put("formatFingerprint", proof.formatFingerprintHex());
            node.put("genesisId", proof.genesisIdHex());
            node.put("proofEncodingId", proof.proofEncodingId());
            node.put("nativeVersioning", Boolean.TRUE.equals(proof.nativeVersioning()));
            node.put("physicalDelete", Boolean.TRUE.equals(proof.physicalDelete()));
            node.put("version", proof.committedHeight());
            node.put("oldestProvableHeight", proof.oldestProvableHeight());
            if (proof.block() != null) node.put("blockHash", proof.block().blockHashHex());
            if (includeProof && proof.block() != null && proof.finalityCertificate() != null) {
                AppChainClient.CertifiedBlockHeader block = proof.block();
                ObjectNode blockNode = node.putObject("block");
                blockNode.put("version", block.version());
                blockNode.put("height", block.height());
                blockNode.put("prevHash", block.prevHashHex());
                blockNode.put("l1Slot", block.l1Slot());
                blockNode.put("l1BlockHash", block.l1BlockHashHex());
                blockNode.put("timestamp", block.timestamp());
                blockNode.put("messagesRoot", block.messagesRootHex());
                blockNode.put("stateRoot", block.stateRootHex());
                blockNode.put("blockHash", block.blockHashHex());
                ObjectNode certificate = node.putObject("finalityCertificate");
                certificate.put("scheme", proof.finalityCertificate().scheme());
                ArrayNode signatures = certificate.putArray("signatures");
                proof.finalityCertificate().signatures().forEach(signature -> {
                    ObjectNode item = signatures.addObject();
                    item.put("signer", signature.signerHex());
                    item.put("signature", signature.signatureHex());
                });
            }
        }
        return node;
    }

    private void print(JsonNode value, PrintWriter out) throws IOException {
        out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            String option = args[index];
            if (!option.startsWith("--") || index + 1 >= args.length) {
                throw new Usage("State options must be --name <value> pairs");
            }
            String name = option.substring(2);
            if (name.isBlank() || values.putIfAbsent(name, args[index + 1]) != null) {
                throw new Usage("Duplicate or empty state option: " + option);
            }
        }
        return values;
    }

    private static void rejectUnknown(Map<String, String> options, Set<String> allowed) {
        for (String name : options.keySet()) {
            if (!allowed.contains(name)) throw new Usage("Unknown state option: --" + name);
        }
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new Usage("--" + name + " is required");
        return value;
    }

    private static long requiredPositiveLong(Map<String, String> options, String name) {
        Long value = optionalPositiveLong(options, name);
        if (value == null) throw new Usage("--" + name + " is required");
        return value;
    }

    private static Long optionalPositiveLong(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new Usage("--" + name + " must be a positive integer");
        }
    }

    private static ProofVerifier.TrustedRootSource parseRootSource(String source) {
        return switch (source) {
            case "locally-verified-block" -> ProofVerifier.TrustedRootSource.LOCALLY_VERIFIED_BLOCK;
            case "finality-certificate" -> ProofVerifier.TrustedRootSource.FINALITY_CERTIFICATE;
            case "cardano-anchor" -> ProofVerifier.TrustedRootSource.CARDANO_ANCHOR;
            case "caller-pinned" -> ProofVerifier.TrustedRootSource.CALLER_PINNED;
            default -> throw new Usage("Unsupported --root-source: " + source);
        };
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) return "unknown failure";
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }

    private static final class Usage extends IllegalArgumentException {
        private Usage(String message) {
            super(message);
        }
    }
}
