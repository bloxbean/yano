package com.bloxbean.cardano.yano.appchain.eutxo.client;

import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            Options:
              --url <api-base>       default http://127.0.0.1:7070/api/v1
              --chain <chain-id>     default eutxo-chain
              --api-key-env <name>   environment variable containing the API key
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
    }

    private static final class Usage extends IllegalArgumentException {
        private Usage(String message) {
            super(message);
        }
    }
}
