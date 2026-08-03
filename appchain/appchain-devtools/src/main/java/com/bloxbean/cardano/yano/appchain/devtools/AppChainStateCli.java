package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.Hex;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** ADR-025 online state inspection and offline trusted-root proof verification. */
final class AppChainStateCli {
    private static final int MAX_PROOF_FILE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> COMMANDS = Set.of(
            "entry", "proof", "verify", "identity", "integrity", "snapshot", "oldest");
    static final String USAGE = """
            Usage: ./yano.sh appchain state entry|proof --url <api-base> --chain <id>
                     --key <hex> [--height <n>] [--api-key <key>]
               or: ./yano.sh appchain state verify --proof-file <json>
                     --trusted-root <hex> --profile <id> --genesis-id <hex|legacy>
                     --chain <id> --height <n> [--root-source <source>]
               or: ./yano.sh appchain state identity|integrity|oldest --url <api-base> --chain <id> [--api-key <key>]
               or: ./yano.sh appchain state snapshot --url <api-base> --chain <id>
                     --path <server-path> [--api-key <key>]

            Trusted root sources: locally-verified-block, finality-certificate, cardano-anchor, caller-pinned
            Verification never defaults to the root carried by the proof envelope.
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
            err.println("State proof file could not be read");
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
        if ("legacy".equals(genesis)) genesis = "";
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
            node.put("dependencyDescriptor", proof.dependencyDescriptor());
            node.put("formatFingerprint", proof.formatFingerprintHex());
            node.put("genesisId", proof.genesisIdHex());
            node.put("legacy", Boolean.TRUE.equals(proof.legacy()));
            node.put("nativeProofEncoding", proof.nativeProofEncoding());
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
