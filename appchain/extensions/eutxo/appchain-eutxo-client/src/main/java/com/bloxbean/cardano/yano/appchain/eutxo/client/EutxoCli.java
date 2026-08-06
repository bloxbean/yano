package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Product CLI implemented over the same root-fixed Java client API. */
public final class EutxoCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_INVALID = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_IO = 74;

    public static final String USAGE = """
            Usage: ./yano.sh appchain eutxo transaction submit <tx.cbor> [options]
               or: ./yano.sh appchain eutxo transaction status <tx-id> [options]
               or: ./yano.sh appchain eutxo utxo get <tx-id#index> [options]
               or: ./yano.sh appchain eutxo utxo list <address> [options]
               or: ./yano.sh appchain eutxo proof <tx-id#index> [options]
               or: ./yano.sh appchain eutxo doctor [--expected-profile-digest <hex>] [options]
               or: ./yano.sh appchain eutxo nullifier reconstruct --ids <file> [--shard N] [--expected-root <hex>]
               or: ./yano.sh appchain eutxo nullifier proof <claim-id-hex> --ids <file>
            Options:
              --url <api-base>       default http://127.0.0.1:7070/api/v1
              --chain <chain-id>     default eutxo-chain
              --api-key-env <name>   environment variable containing the API key
              --ids <file>           settled claim ids (one 32-byte hex per line) for nullifier commands
              --shard <0-15>         restrict/compare a single nullifier shard
              --expected-root <hex>  on-chain shard root to compare a reconstruction against
            """.stripTrailing();

    private static final ObjectMapper JSON = new ObjectMapper();

    private EutxoCli() {
    }

    public static int run(String[] arguments, PrintWriter out, PrintWriter err) {
        try {
            Options options = Options.parse(arguments);
            if (options.help) {
                out.println(USAGE);
                return EXIT_OK;
            }
            // The nullifier reconstruction/proof tooling is standalone: it
            // rebuilds shard roots from L1 spend history with no node access.
            if (!options.command.isEmpty()
                    && options.command.getFirst().equals("nullifier")) {
                return nullifier(options, out);
            }
            EutxoClient client = new EutxoClient(options.client());
            return execute(options, client, out);
        } catch (Usage failure) {
            err.println(failure.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        } catch (IOException failure) {
            err.println("EUTxO input/output failed");
            return EXIT_IO;
        } catch (RuntimeException failure) {
            err.println("EUTxO command failed: " + safe(failure.getMessage()));
            return EXIT_INVALID;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static int execute(Options options, EutxoClient client, PrintWriter out)
            throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (options.command.equals(List.of("transaction", "submit"))) {
            byte[] transaction = Files.readAllBytes(Path.of(options.argument));
            AppChainClient.SubmitResult submitted = client.submit(transaction);
            result.put("status", "SUBMITTED");
            result.put("messageId", submitted.messageId());
            result.put("chainId", submitted.chainId());
        } else if (options.command.equals(List.of("transaction", "status"))) {
            EutxoSnapshot<?> snapshot = client.transactionSnapshot(options.argument);
            result.put("transactionId", options.argument);
            result.put("receipt", snapshot.value());
            root(result, snapshot);
        } else if (options.command.equals(List.of("utxo", "get"))) {
            EutxoSnapshot<?> snapshot =
                    client.utxoSnapshot(EutxoOutpoint.parse(options.argument));
            result.put("outpoint", options.argument);
            result.put("utxo", snapshot.value());
            root(result, snapshot);
        } else if (options.command.equals(List.of("utxo", "list"))) {
            EutxoSnapshot<?> snapshot = client.utxosSnapshot(options.argument);
            result.put("address", options.argument);
            result.put("utxos", snapshot.value());
            root(result, snapshot);
        } else if (options.command.equals(List.of("proof"))) {
            EutxoOutpoint outpoint = EutxoOutpoint.parse(options.argument);
            result.put("outpoint", options.argument);
            result.put("proof", client.proof(outpoint).orElse(null));
        } else if (options.command.equals(List.of("doctor"))) {
            EutxoSnapshot<String> snapshot = client.profileSnapshot();
            boolean matches = options.expectedDigest == null
                    || options.expectedDigest.equals(snapshot.value());
            result.put("status", matches ? "PASS" : "FAIL");
            result.put("profileDigest", snapshot.value());
            result.put("expectedProfileDigest", options.expectedDigest);
            root(result, snapshot);
            out.println(JSON.writeValueAsString(result));
            return matches ? EXIT_OK : EXIT_INVALID;
        } else {
            throw new Usage("unknown EUTxO command");
        }
        out.println(JSON.writeValueAsString(result));
        return EXIT_OK;
    }

    /**
     * Standalone nullifier tooling (ADR-UTXO-009 SP-M4): rebuild a shard trie
     * from the settled claim ids extracted from L1 spend history, so anyone —
     * a cranker with no surviving L2 node — can verify a shard root or produce
     * a proof against it. {@code reconstruct} rebuilds and (optionally)
     * compares to the on-chain root; {@code proof} emits the MPF wire proof
     * for one claim id against the reconstructed root.
     */
    private static int nullifier(Options options, PrintWriter out)
            throws IOException {
        List<byte[]> ids = readClaimIds(options.idsPath);
        Map<String, Object> result = new LinkedHashMap<>();
        if (options.command.equals(List.of("nullifier", "reconstruct"))) {
            Map<Integer, List<byte[]>> byShard = groupByShard(ids, options.shard);
            Map<String, Object> roots = new TreeMap<>();
            for (Map.Entry<Integer, List<byte[]>> entry : byShard.entrySet()) {
                roots.put(Integer.toString(entry.getKey()), HexFormat.of()
                        .formatHex(NullifierShardMirror.reconstructShardRoot(
                                entry.getValue())));
            }
            result.put("command", "reconstruct");
            result.put("claimCount", ids.size());
            result.put("shardRoots", roots);
            if (options.expectedRoot != null) {
                if (options.shard == null || byShard.size() != 1) {
                    throw new Usage(
                            "--expected-root requires a single --shard to compare");
                }
                String actual = (String) roots.values().iterator().next();
                boolean matches = options.expectedRoot
                        .equalsIgnoreCase(actual);
                result.put("expectedRoot", options.expectedRoot.toLowerCase());
                result.put("status", matches ? "MATCH" : "MISMATCH");
                out.println(JSON.writeValueAsString(result));
                return matches ? EXIT_OK : EXIT_INVALID;
            }
            out.println(JSON.writeValueAsString(result));
            return EXIT_OK;
        }
        if (options.command.equals(List.of("nullifier", "proof"))) {
            byte[] claim = parseClaimId(options.argument);
            int shard = NullifierShardMirror.shardOf(claim);
            NullifierShardMirror mirror = new NullifierShardMirror();
            boolean settled = false;
            for (byte[] id : ids) {
                if (NullifierShardMirror.shardOf(id) == shard) {
                    mirror.insert(id);
                    settled |= java.util.Arrays.equals(id, claim);
                }
            }
            byte[] root = mirror.root(shard);
            byte[] wire = mirror.proofWire(shard, claim).orElseThrow(
                    () -> new IllegalStateException("shard could not produce a proof"));
            boolean verified = settled
                    ? NullifierShardMirror.verifyMembership(root, claim, wire)
                    : NullifierShardMirror.verifyAbsence(root, claim, wire);
            result.put("command", "proof");
            result.put("claimId", HexFormat.of().formatHex(claim));
            result.put("shard", shard);
            result.put("shardRoot", HexFormat.of().formatHex(root));
            result.put("kind", settled ? "membership" : "non-membership");
            result.put("proofWire", HexFormat.of().formatHex(wire));
            result.put("verified", verified);
            out.println(JSON.writeValueAsString(result));
            return verified ? EXIT_OK : EXIT_INVALID;
        }
        throw new Usage("unknown nullifier command");
    }

    private static Map<Integer, List<byte[]>> groupByShard(
            List<byte[]> ids, Integer only) {
        Map<Integer, List<byte[]>> byShard = new TreeMap<>();
        for (byte[] id : ids) {
            int shard = NullifierShardMirror.shardOf(id);
            if (only != null && shard != only) {
                throw new Usage("claim id " + HexFormat.of().formatHex(id)
                        + " does not belong to --shard " + only);
            }
            byShard.computeIfAbsent(shard, key -> new ArrayList<>()).add(id);
        }
        if (only != null) {
            byShard.computeIfAbsent(only, key -> new ArrayList<>());
        }
        return byShard;
    }

    private static List<byte[]> readClaimIds(String idsPath) throws IOException {
        if (idsPath == null) {
            throw new Usage("--ids <file> is required (one claim id hex per line)");
        }
        List<byte[]> ids = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(idsPath))) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            ids.add(parseClaimId(trimmed));
        }
        return ids;
    }

    private static byte[] parseClaimId(String hex) {
        if (hex == null) {
            throw new Usage("a 32-byte claim id (hex) is required");
        }
        byte[] id;
        try {
            id = HexFormat.of().parseHex(hex.trim());
        } catch (RuntimeException failure) {
            throw new Usage("claim id must be canonical hex: " + safe(hex));
        }
        if (id.length != 32) {
            throw new Usage("claim id must be exactly 32 bytes");
        }
        return id;
    }

    private static void root(Map<String, Object> result, EutxoSnapshot<?> snapshot) {
        result.put("chainId", snapshot.chainId());
        result.put("committedHeight", snapshot.committedHeight());
        result.put("stateRoot", snapshot.stateRootHex());
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "operation rejected";
        }
        return message.replaceAll("[\\r\\n\\t]", " ").substring(
                0, Math.min(message.length(), 240));
    }

    private static final class Options {
        private String url = "http://127.0.0.1:7070/api/v1";
        private String chain = "eutxo-chain";
        private String apiKeyEnvironment;
        private String expectedDigest;
        private String argument;
        private String idsPath;
        private Integer shard;
        private String expectedRoot;
        private boolean help;
        private List<String> command = List.of();

        private static Options parse(String[] arguments) {
            Options options = new Options();
            List<String> positional = new ArrayList<>();
            for (int index = 0; index < arguments.length; index++) {
                String value = arguments[index];
                switch (value) {
                    case "-h", "--help" -> options.help = true;
                    case "--url" -> options.url = required(arguments, ++index, value);
                    case "--chain" -> options.chain = required(arguments, ++index, value);
                    case "--api-key-env" ->
                            options.apiKeyEnvironment = required(arguments, ++index, value);
                    case "--expected-profile-digest" ->
                            options.expectedDigest = required(arguments, ++index, value);
                    case "--ids" -> options.idsPath = required(arguments, ++index, value);
                    case "--expected-root" ->
                            options.expectedRoot = required(arguments, ++index, value);
                    case "--shard" -> options.shard = parseShard(
                            required(arguments, ++index, value));
                    default -> {
                        if (value.startsWith("--")) {
                            throw new Usage("unknown option: " + value);
                        }
                        positional.add(value);
                    }
                }
            }
            if (options.help) {
                return options;
            }
            if (positional.equals(List.of("doctor"))) {
                options.command = List.copyOf(positional);
                return options;
            }
            if (positional.size() == 2 && positional.getFirst().equals("proof")) {
                options.command = List.of("proof");
                options.argument = positional.get(1);
                return options;
            }
            if (positional.equals(List.of("nullifier", "reconstruct"))) {
                options.command = List.copyOf(positional);
                return options;
            }
            if (positional.size() == 3
                    && positional.subList(0, 2).equals(List.of("nullifier", "proof"))) {
                options.command = List.of("nullifier", "proof");
                options.argument = positional.get(2);
                return options;
            }
            if (positional.size() == 3
                    && (positional.subList(0, 2).equals(List.of("transaction", "submit"))
                    || positional.subList(0, 2).equals(List.of("transaction", "status"))
                    || positional.subList(0, 2).equals(List.of("utxo", "get"))
                    || positional.subList(0, 2).equals(List.of("utxo", "list")))) {
                options.command = List.copyOf(positional.subList(0, 2));
                options.argument = positional.get(2);
                return options;
            }
            throw new Usage("an EUTxO command and its required argument are needed");
        }

        private AppChainClient client() {
            if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                throw new Usage("--url must be an HTTP(S) API base");
            }
            if (chain == null || chain.isBlank()) {
                throw new Usage("--chain must not be blank");
            }
            AppChainClient.Builder builder = AppChainClient.builder(url).chainId(chain);
            if (apiKeyEnvironment != null) {
                String secret = Optional.ofNullable(System.getenv(apiKeyEnvironment))
                        .filter(value -> !value.isBlank())
                        .orElseThrow(() -> new Usage(
                                "API key environment variable is not set"));
                builder.apiKey(secret);
            }
            return builder.build();
        }

        private static String required(String[] values, int index, String option) {
            if (index >= values.length || values[index].startsWith("--")) {
                throw new Usage(option + " requires a value");
            }
            return values[index];
        }

        private static int parseShard(String value) {
            int shard;
            try {
                shard = Integer.parseInt(value.trim());
            } catch (NumberFormatException failure) {
                throw new Usage("--shard must be an integer 0.."
                        + (NullifierShardMirror.SHARD_COUNT - 1));
            }
            if (shard < 0 || shard >= NullifierShardMirror.SHARD_COUNT) {
                throw new Usage("--shard must be 0.."
                        + (NullifierShardMirror.SHARD_COUNT - 1));
            }
            return shard;
        }
    }

    private static final class Usage extends IllegalArgumentException {
        private Usage(String message) {
            super(message);
        }
    }
}
