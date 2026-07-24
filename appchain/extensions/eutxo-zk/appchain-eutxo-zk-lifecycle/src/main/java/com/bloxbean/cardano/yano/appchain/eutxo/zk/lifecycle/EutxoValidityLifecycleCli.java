package com.bloxbean.cardano.yano.appchain.eutxo.zk.lifecycle;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.client.EutxoL2SessionKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Command-line facade for the project-aware EUTxO validity lifecycle. */
public final class EutxoValidityLifecycleCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_INVALID = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_IO = 74;

    public static final String USAGE = """
            Usage: ./yano.sh appchain validity key generate --output <file> --password-env <name>
               or: ./yano.sh appchain validity bootstrap --project <dir> [--development-ceremony --yes]
               or: ./yano.sh appchain validity status --project <dir>
               or: ./yano.sh appchain validity prove --project <dir> --previous-root <hex> --transition <file>...
               or: ./yano.sh appchain validity proof <proof-id> --project <dir>
               or: ./yano.sh appchain validity doctor --project <dir>
               or: ./yano.sh appchain validity <deposit|settlement|withdrawal|recovery> prepare
                       --project <dir> --id <id> [--request <json>] [--proof <proof-id>]
               or: ./yano.sh appchain validity <deposit|settlement|withdrawal|recovery> submit
                       --project <dir> --id <id> --tx <signed-cardano.cbor>
                       --url <node-origin> [--api-key-env <name>]
               or: ./yano.sh appchain validity <deposit|settlement|withdrawal|recovery> stable
                       --project <dir> --id <id> --tx-id <cardano-tx-id>
               or: ./yano.sh appchain validity reconcile --project <dir>

            Safety:
              Key generation writes an encrypted local Jubjub session key and prints only its public key.
              zeroj-jubjub-dev-v1 always requires a trusted prover and disposable test funds.
              Preview and Preprod require a durable project acknowledgement.
              Mainnet is rejected unconditionally.
            """.stripTrailing();

    private static final ObjectMapper JSON = new ObjectMapper();

    private EutxoValidityLifecycleCli() {
    }

    public static int run(
            String[] arguments,
            PrintWriter out,
            PrintWriter err
    ) {
        try {
            Options options = Options.parse(arguments);
            if (options.help) {
                out.println(USAGE);
                return EXIT_OK;
            }
            if (options.command.equals(List.of("key", "generate"))) {
                out.println(JSON.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(generateKey(
                                options.output,
                                password(options.passwordEnvironment))));
                return EXIT_OK;
            }
            EutxoValidityLifecycle lifecycle =
                    new EutxoValidityLifecycle(options.project);
            Object result = execute(options, lifecycle);
            out.println(JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(result));
            if (result instanceof EutxoValidityLifecycle.DoctorReport doctor
                    && doctor.status().endsWith("_FAILED")) {
                return EXIT_INVALID;
            }
            if (result instanceof EutxoValidityLifecycle.Result lifecycleResult
                    && "PROOF_INVALID".equals(lifecycleResult.status())) {
                return EXIT_INVALID;
            }
            return EXIT_OK;
        } catch (Usage failure) {
            err.println(failure.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        } catch (IOException failure) {
            err.println("EUTxO validity lifecycle I/O failed: "
                    + safe(failure.getMessage()));
            return EXIT_IO;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("EUTxO validity lifecycle was interrupted");
            return EXIT_IO;
        } catch (RuntimeException failure) {
            err.println("EUTxO validity lifecycle rejected the operation: "
                    + safe(failure.getMessage()));
            return EXIT_INVALID;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static Object execute(
            Options options,
            EutxoValidityLifecycle lifecycle
    ) throws IOException, InterruptedException {
        if (options.command.equals(List.of("bootstrap"))) {
            return lifecycle.bootstrap(
                    options.developmentCeremony, options.confirmed);
        }
        if (options.command.equals(List.of("status"))) {
            return lifecycle.status();
        }
        if (options.command.equals(List.of("prove"))) {
            return lifecycle.prove(
                    options.transitions, options.previousRoot);
        }
        if (options.command.equals(List.of("proof"))) {
            return lifecycle.proof(options.argument);
        }
        if (options.command.equals(List.of("doctor"))) {
            return lifecycle.doctor();
        }
        if (options.command.equals(List.of("reconcile"))) {
            return lifecycle.reconcile();
        }
        if (options.command.size() == 2) {
            String kind = options.command.getFirst();
            return switch (options.command.get(1)) {
                case "prepare" -> lifecycle.prepareOperation(
                        kind, options.operationId,
                        options.request, options.proofId);
                case "submit" -> lifecycle.submitOperation(
                        kind, options.operationId,
                        options.transaction, options.url,
                        secret(options.apiKeyEnvironment));
                case "stable" -> lifecycle.markStable(
                        kind, options.operationId,
                        options.transactionId);
                default -> throw new Usage(
                        "unknown validity lifecycle action");
            };
        }
        throw new Usage("unknown validity lifecycle command");
    }

    private static String secret(String environment) {
        if (environment == null) {
            return null;
        }
        return Optional.ofNullable(System.getenv(environment))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new Usage(
                        "API key environment variable is not set"));
    }

    private static char[] password(String environment) {
        if (environment == null) {
            throw new Usage("key generate requires --password-env");
        }
        String value = Optional.ofNullable(System.getenv(environment))
                .filter(item -> !item.isBlank())
                .orElseThrow(() -> new Usage(
                        "password environment variable is not set"));
        return value.toCharArray();
    }

    static Map<String, String> generateKey(
            Path output,
            char[] password
    ) throws IOException {
        if (output == null) {
            throw new Usage("key generate requires --output");
        }
        Path target = output.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new Usage("key output must have a parent directory");
        }
        Files.createDirectories(parent);
        byte[] encrypted;
        String publicKey;
        try (EutxoL2SessionKey key = EutxoL2SessionKey.random()) {
            encrypted = key.encrypt(password);
            publicKey = HexFormat.of().formatHex(key.publicKey());
        } finally {
            Arrays.fill(password, '\0');
        }
        boolean created = false;
        try {
            Files.write(target, encrypted,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            created = true;
            try {
                Files.setPosixFilePermissions(
                        target,
                        EnumSet.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows and other non-POSIX file systems rely on their ACLs.
            }
        } catch (IOException failure) {
            if (created) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
        return Map.of(
                "status", "L2_SESSION_KEY_CREATED",
                "encryptedKey", target.toString(),
                "publicKey", publicKey);
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "operation rejected";
        }
        String value = message.replaceAll("[\\r\\n\\t]", " ");
        return value.substring(0, Math.min(value.length(), 240));
    }

    private static final class Options {
        private Path project = Path.of(".");
        private final List<Path> transitions = new ArrayList<>();
        private List<String> command = List.of();
        private String argument;
        private String previousRoot;
        private String operationId;
        private String proofId;
        private Path request;
        private Path transaction;
        private URI url;
        private String transactionId;
        private String apiKeyEnvironment;
        private String passwordEnvironment;
        private Path output;
        private boolean developmentCeremony;
        private boolean confirmed;
        private boolean help;

        private static Options parse(String[] arguments) {
            Options options = new Options();
            List<String> positional = new ArrayList<>();
            for (int index = 0; index < arguments.length; index++) {
                String value = arguments[index];
                switch (value) {
                    case "-h", "--help" -> options.help = true;
                    case "--project" -> options.project = Path.of(
                            required(arguments, ++index, value));
                    case "--transition" -> options.transitions.add(
                            Path.of(required(arguments, ++index, value)));
                    case "--previous-root" -> options.previousRoot =
                            once(options.previousRoot,
                                    required(arguments, ++index, value),
                                    value);
                    case "--id" -> options.operationId =
                            once(options.operationId,
                                    required(arguments, ++index, value),
                                    value);
                    case "--proof" -> options.proofId =
                            once(options.proofId,
                                    required(arguments, ++index, value),
                                    value);
                    case "--request" -> options.request = Path.of(
                            required(arguments, ++index, value));
                    case "--tx" -> options.transaction = Path.of(
                            required(arguments, ++index, value));
                    case "--tx-id" -> options.transactionId =
                            once(options.transactionId,
                                    required(arguments, ++index, value),
                                    value);
                    case "--url" -> options.url = URI.create(
                            required(arguments, ++index, value));
                    case "--api-key-env" -> options.apiKeyEnvironment =
                            once(options.apiKeyEnvironment,
                                    required(arguments, ++index, value),
                                    value);
                    case "--password-env" ->
                            options.passwordEnvironment =
                                    once(options.passwordEnvironment,
                                            required(arguments, ++index, value),
                                            value);
                    case "--output" -> options.output = Path.of(
                            required(arguments, ++index, value));
                    case "--development-ceremony" ->
                            options.developmentCeremony = true;
                    case "--yes" -> options.confirmed = true;
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
            options.command = command(positional, options);
            validate(options);
            return options;
        }

        private static List<String> command(
                List<String> positional,
                Options options
        ) {
            if (positional.size() == 1
                    && List.of("bootstrap", "status", "prove",
                    "doctor", "reconcile")
                    .contains(positional.getFirst())) {
                return List.copyOf(positional);
            }
            if (positional.size() == 2
                    && positional.equals(List.of("key", "generate"))) {
                return List.copyOf(positional);
            }
            if (positional.size() == 2
                    && "proof".equals(positional.getFirst())) {
                options.argument = positional.get(1);
                return List.of("proof");
            }
            if (positional.size() == 2
                    && List.of("deposit", "settlement",
                    "withdrawal", "recovery")
                    .contains(positional.getFirst())
                    && List.of("prepare", "submit", "stable")
                    .contains(positional.get(1))) {
                return List.copyOf(positional);
            }
            throw new Usage(
                    "a supported validity lifecycle command is required");
        }

        private static void validate(Options options) {
            if (options.command.equals(List.of("key", "generate"))) {
                if (options.output == null
                        || options.passwordEnvironment == null) {
                    throw new Usage(
                            "key generate requires --output and --password-env");
                }
                return;
            }
            if (options.command.equals(List.of("prove"))
                    && (options.previousRoot == null
                    || options.transitions.isEmpty())) {
                throw new Usage(
                        "prove requires --previous-root and --transition");
            }
            if (options.command.size() != 2) {
                return;
            }
            String action = options.command.get(1);
            if (options.operationId == null) {
                throw new Usage(action + " requires --id");
            }
            if ("prepare".equals(action)
                    && options.request == null
                    && options.proofId == null) {
                throw new Usage(
                        "prepare requires --request or --proof");
            }
            if ("submit".equals(action)
                    && (options.transaction == null
                    || options.url == null)) {
                throw new Usage(
                        "submit requires --tx and --url");
            }
            if ("stable".equals(action)
                    && options.transactionId == null) {
                throw new Usage(
                        "stable requires --tx-id");
            }
        }

        private static String required(
                String[] values,
                int index,
                String option
        ) {
            if (index >= values.length
                    || values[index].startsWith("--")) {
                throw new Usage(option + " requires a value");
            }
            return values[index];
        }

        private static String once(
                String current,
                String value,
                String option
        ) {
            if (current != null) {
                throw new Usage(option + " may be specified once");
            }
            return value;
        }
    }

    private static final class Usage extends IllegalArgumentException {
        private Usage(String message) {
            super(message);
        }
    }
}
