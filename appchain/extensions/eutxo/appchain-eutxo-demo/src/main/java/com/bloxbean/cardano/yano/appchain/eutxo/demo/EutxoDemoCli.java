package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Public {@code yano appchain eutxo demo} command. */
public final class EutxoDemoCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_INVALID = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_IO = 74;

    public static final String USAGE = """
            Usage: ./yano.sh appchain eutxo demo setup [options]
               or: ./yano.sh appchain eutxo demo start|up|stop [options]
               or: ./yano.sh appchain eutxo demo status [options]
               or: ./yano.sh appchain eutxo demo ceremony --yes [options]
               or: ./yano.sh appchain eutxo demo round-trip|verify [options]
               or: ./yano.sh appchain eutxo demo deposit-build [options]
               or: ./yano.sh appchain eutxo demo deposit-submit [options]
               or: ./yano.sh appchain eutxo demo reset --yes [options]
            Options:
              --scenario ledger|bridge|zk  default ledger for a new workspace;
                                           inferred for an existing workspace
              --workspace <directory>      default $YANO_HOME/eutxo-demo
              --name <project-name>        default payments-eutxo
              --chain-id <chain-id>        default project name
              --members <count>            default 3
              --count <round-trips>        default 1, maximum 16
              --http-port-base <port>      default 7070
              --server-port-base <port>    default 13337
              --target-base <url>          attach to an externally-owned cluster
                                           (bridge scenario only; the workspace
                                           keeps wallets/journal/artifacts and
                                           never starts or stops the target)
              --operator-seed-file <file>  64-hex raw Ed25519 operator seed in an
                                           owner-only file (openssl rand -hex 32).
                                           Required for settlement on any public
                                           network; settlement-prepare needs only
                                           this, no running node
              --network <name>             devnet (default) | preprod | preview |
                                           sanchonet | mainnet
              --member-keys <hex,hex,...>  the chain's live federation keys, which
                                           the settlement root datum commits to
              --threshold <n>              federation threshold; default majority
              --payout-address <address>   the target chain's configured
                                           withdrawal address (attach setup)
              --address <Cardano address>  external L1 depositor
              --l2-address <address>       defaults to --address
              --l2-public-key <hex>        required for an external ZK user
              --amount <lovelace>          external deposit amount
              --output <file>              unsigned transaction output
              --signed-transaction <file>  externally signed transaction CBOR
              --format text|json           default text
            """.stripTrailing();

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EutxoDemoScenarioRegistry scenarios;

    public EutxoDemoCli() {
        this(new EutxoDemoScenarioRegistry());
    }

    EutxoDemoCli(EutxoDemoScenarioRegistry scenarios) {
        this.scenarios = scenarios;
    }

    public static int run(String[] arguments, PrintWriter out, PrintWriter err) {
        return new EutxoDemoCli().execute(arguments, out, err);
    }

    int execute(String[] arguments, PrintWriter out, PrintWriter err) {
        try {
            EutxoDemoOptions options = parse(arguments);
            if (options.help()) {
                out.println(USAGE);
                return EXIT_OK;
            }
            EutxoDemoResult result = command(options);
            render(result, options.format(), out);
            return EXIT_OK;
        } catch (Usage failure) {
            err.println(failure.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        } catch (IOException failure) {
            err.println("EUTxO demo I/O failed");
            return EXIT_IO;
        } catch (RuntimeException failure) {
            err.println("EUTxO demo rejected the operation: " + safe(failure.getMessage()));
            return EXIT_INVALID;
        } catch (Exception failure) {
            err.println("EUTxO demo operation failed");
            return EXIT_INVALID;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static String requireTarget(EutxoDemoOptions options) {
        String base = options.targetBase();
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException(
                    "settlement commands need --target-base");
        }
        return base;
    }

    private EutxoDemoResult command(EutxoDemoOptions options) throws Exception {
        if ("scenarios".equals(options.command())) {
            List<Map<String, Object>> inventory = scenarios.all().stream()
                    .map(provider -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", provider.id());
                        item.put("version", provider.version());
                        item.put("maturity", provider.maturity());
                        item.put("recipe", provider.recipe());
                        item.put("operations", provider.operations().stream().sorted().toList());
                        return item;
                    }).toList();
            return EutxoDemoResult.of("EUTXO_DEMO_SCENARIOS",
                    Map.of("scenarios", inventory));
        }
        // ADR-UTXO-009: deploy the showcase settlement identity on the L1 —
        // the two one-shot mints plus the genesis threads — and drop the
        // parameterized validators where the chain config expects them.
        // Idempotent: a live root thread short-circuits.
        if ("settlement-bootstrap".equals(options.command())) {
            String base = options.targetBase();
            if (base == null || base.isBlank()) {
                throw new IllegalArgumentException(
                        "settlement-bootstrap needs --target-base");
            }
            java.nio.file.Path scriptDir = options.output();
            if (scriptDir == null) {
                throw new IllegalArgumentException(
                        "settlement-bootstrap needs --output <config/settlement dir>");
            }
            // Public networks: the operator supplies their OWN key, and the
            // identity is built on the production profile.
            if (options.operatorSeedFile() != null) {
                return EutxoDemoResult.of("EUTXO_SETTLEMENT_BOOTSTRAP",
                        SettlementDeployment.bootstrap(
                                base,
                                SettlementOperatorIdentity.fromKeyFile(
                                        options.operatorSeedFile()),
                                options.network(),
                                memberKeys(options),
                                effectiveThreshold(options),
                                options.operatorSeedFile().toAbsolutePath()
                                        .getParent(),
                                options.operatorSeedFile(), scriptDir));
            }
            requireDevnetDemoActors(options, "settlement-bootstrap");
            String transaction = SettlementBootstrapWorkflow
                    .bootstrapShowcaseDevnet(base + "/api/v1/");
            ShowcaseSettlementPlan.writeScripts(
                    ShowcaseSettlementPlan.PLAN, scriptDir);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chainId", ShowcaseSettlementPlan.CHAIN_ID);
            payload.put("bootstrapTransaction", transaction);
            payload.put("vaultAddress",
                    ShowcaseSettlementPlan.PLAN.vaultAddress());
            payload.put("shardAddress",
                    ShowcaseSettlementPlan.PLAN.shardAddress());
            payload.put("rootAddress",
                    ShowcaseSettlementPlan.PLAN.rootAddress());
            payload.put("operatorAddress",
                    ShowcaseSettlementPlan.OPERATOR_ADDRESS);
            payload.put("scriptDirectory", scriptDir.toString());
            return EutxoDemoResult.of("EUTXO_SETTLEMENT_BOOTSTRAP", payload);
        }
        // Public-network step 1: where to send the funds, and how much.
        if ("settlement-prepare".equals(options.command())) {
            if (options.operatorSeedFile() == null) {
                throw new IllegalArgumentException("settlement-prepare needs"
                        + " --operator-seed-file (generate one:"
                        + " openssl rand -hex 32 > operator.seed)");
            }
            // No --target-base: preparing needs no node.
            return EutxoDemoResult.of("EUTXO_SETTLEMENT_PREPARE",
                    SettlementDeployment.prepare(
                            SettlementOperatorIdentity.fromKeyFile(
                                    options.operatorSeedFile()),
                            options.network()));
        }
        if ("settlement-deposit".equals(options.command())) {
            SettlementOperatorIdentity identity = settlementIdentity(
                    options, "settlement-deposit");
            return EutxoDemoResult.of("EUTXO_SETTLEMENT_DEPOSIT",
                    ShowcaseSettlementDemo.deposit(
                            requireTarget(options), options.amount(), identity));
        }
        if ("settlement-withdraw".equals(options.command())) {
            return EutxoDemoResult.of("EUTXO_SETTLEMENT_WITHDRAW",
                    ShowcaseSettlementDemo.withdraw(requireTarget(options),
                            settlementIdentity(options, "settlement-withdraw")));
        }
        if ("settlement-status".equals(options.command())) {
            return EutxoDemoResult.of("EUTXO_SETTLEMENT_STATUS",
                    ShowcaseSettlementDemo.status(requireTarget(options)));
        }
        if ("settlement-info".equals(options.command())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chainId", ShowcaseSettlementPlan.CHAIN_ID);
            payload.put("profile",
                    ShowcaseSettlementPlan.PLAN.profile().id());
            payload.put("profileDigest",
                    ShowcaseSettlementPlan.PLAN.profile().digestHex());
            payload.put("fallbackDelaySlots",
                    ShowcaseSettlementPlan.FALLBACK_DELAY_SLOTS);
            payload.put("vaultAddress",
                    ShowcaseSettlementPlan.PLAN.vaultAddress());
            payload.put("shardAddress",
                    ShowcaseSettlementPlan.PLAN.shardAddress());
            payload.put("rootAddress",
                    ShowcaseSettlementPlan.PLAN.rootAddress());
            payload.put("operatorAddress",
                    ShowcaseSettlementPlan.OPERATOR_ADDRESS);
            payload.put("payoutAddress",
                    ShowcaseSettlementPlan.PAYOUT_ADDRESS);
            payload.put("members", ShowcaseSettlementPlan.CLUSTER_MEMBERS);
            payload.put("threshold", ShowcaseSettlementPlan.THRESHOLD);
            return EutxoDemoResult.of("EUTXO_SETTLEMENT_INFO", payload);
        }
        if ("setup".equals(options.command())) {
            EutxoDemoScenarioProvider provider = scenarios.require(options.scenario());
            EutxoDemoWorkspace workspace = EutxoDemoWorkspace.create(options, provider);
            provider.setup(workspace, options);
            return ready(workspace);
        }
        if ("up".equals(options.command())
                && !java.nio.file.Files.exists(
                options.workspace().resolve(".yano-eutxo-demo"))) {
            EutxoDemoScenarioProvider provider = scenarios.require(
                    options.scenario() == null ? "ledger" : options.scenario());
            EutxoDemoWorkspace workspace = EutxoDemoWorkspace.create(options, provider);
            provider.setup(workspace, options);
            return withIndex(
                    provider.execute("up", workspace, options),
                    workspace, true);
        }
        EutxoDemoWorkspace workspace = EutxoDemoWorkspace.open(options.workspace());
        if (options.scenario() != null
                && !options.scenario().equals(workspace.manifest().scenario())) {
            throw new IllegalArgumentException(
                    "requested scenario conflicts with the workspace manifest");
        }
        if (options.targetBase() != null
                && !options.targetBase().equals(workspace.manifest().targetBase())) {
            throw new IllegalArgumentException(
                    "requested --target-base conflicts with the workspace manifest");
        }
        EutxoDemoScenarioProvider provider =
                scenarios.require(workspace.manifest().scenario());
        if ("status".equals(options.command())) {
            return withIndex(
                    provider.execute("status", workspace, options),
                    workspace, false);
        }
        if ("reset".equals(options.command())) {
            if (!options.confirmed()) {
                throw new Usage("reset requires --yes");
            }
            if (provider.operations().contains("stop")) {
                try {
                    provider.execute("stop", workspace, options);
                } catch (IllegalStateException lifecycle) {
                    if (!workspace.manifest().attached()) {
                        throw lifecycle;
                    }
                    // Externally-owned cluster: nothing to stop locally, and a
                    // lifecycle refusal must never block workspace removal.
                }
            }
            Path removed = workspace.root();
            workspace.reset();
            return EutxoDemoResult.of("EUTXO_DEMO_RESET",
                    Map.of("workspace", removed.toString()));
        }
        if (!provider.operations().contains(options.command())) {
            throw new IllegalArgumentException(
                    options.command() + " is not supported by scenario " + provider.id());
        }
        EutxoDemoResult result =
                provider.execute(options.command(), workspace, options);
        return withIndex(
                result,
                workspace,
                List.of("start", "up", "round-trip", "verify")
                        .contains(options.command()));
    }

    private static EutxoDemoResult withIndex(
            EutxoDemoResult result,
            EutxoDemoWorkspace workspace,
            boolean requireReady
    ) throws InterruptedException {
        EutxoDemoCluster cluster = new EutxoDemoCluster(workspace);
        if (requireReady) {
            cluster.awaitIndexReady(java.time.Duration.ofMinutes(2));
        }
        Map<String, Object> fields = new LinkedHashMap<>(result.fields());
        fields.put("index", cluster.indexSummary());
        fields.put("console", cluster.consoleUrl());
        return EutxoDemoResult.of(result.status(), fields);
    }

    private static EutxoDemoResult ready(EutxoDemoWorkspace workspace) {
        Map<String, Object> fields = common(workspace);
        fields.put("network", workspace.manifest().network());
        if (workspace.manifest().attached()) {
            fields.put("targetBase", workspace.manifest().targetBase());
            fields.put("secrets", "disposable development identities");
            fields.put("next", "./yano.sh appchain eutxo demo round-trip --workspace "
                    + workspace.root());
        } else {
            fields.put("nodes", workspace.manifest().members());
            fields.put("ports", workspace.manifest().httpPortBase() + "-"
                    + (workspace.manifest().httpPortBase()
                    + workspace.manifest().members() - 1));
            fields.put("secrets", "disposable development identities");
            fields.put("next", "./yano.sh appchain eutxo demo start --workspace "
                    + workspace.root());
        }
        return EutxoDemoResult.of("EUTXO_DEMO_READY", fields);
    }

    private static Map<String, Object> common(EutxoDemoWorkspace workspace) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenario", workspace.manifest().scenario());
        fields.put("project", workspace.project().toString());
        fields.put("workspace", workspace.root().toString());
        fields.put("chainId", workspace.manifest().chainId());
        fields.put("maturity", workspace.manifest().maturity());
        return fields;
    }

    private static void render(
            EutxoDemoResult result,
            EutxoDemoOptions.Format format,
            PrintWriter out) throws IOException {
        if (format == EutxoDemoOptions.Format.JSON) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("status", result.status());
            envelope.putAll(result.fields());
            out.println(JSON.writeValueAsString(envelope));
            return;
        }
        out.println(result.status());
        out.println();
        for (Map.Entry<String, Object> field : result.fields().entrySet()) {
            out.printf(Locale.ROOT, "%-14s : %s%n", title(field.getKey()), field.getValue());
        }
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static EutxoDemoOptions parse(String[] arguments) {
        String command = null;
        String scenario = null;
        Path workspace = defaultWorkspace();
        String name = "payments-eutxo";
        String chainId = null;
        int members = 3;
        int count = 1;
        int httpPortBase = 7070;
        int serverPortBase = 13337;
        String targetBase = null;
        Path operatorSeedFile = null;
        String payoutAddress = null;
        String address = null;
        String l2Address = null;
        String l2PublicKey = null;
        long amount = 20_000_000L;
        Path output = null;
        Path signedTransaction = null;
        String network = "devnet";
        String memberKeys = null;
        int threshold = 0;
        EutxoDemoOptions.Format format = EutxoDemoOptions.Format.TEXT;
        boolean confirmed = false;
        boolean help = arguments.length == 0;
        List<String> positional = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            String value = arguments[index];
            switch (value) {
                case "-h", "--help" -> help = true;
                case "--scenario" -> scenario = required(arguments, ++index, value);
                case "--workspace" ->
                        workspace = Path.of(required(arguments, ++index, value));
                case "--name" -> name = name(required(arguments, ++index, value), value);
                case "--chain-id" ->
                        chainId = name(required(arguments, ++index, value), value);
                case "--members" ->
                        members = integer(required(arguments, ++index, value), value, 1, 32);
                case "--count" ->
                        count = integer(required(arguments, ++index, value), value, 1, 16);
                case "--http-port-base" -> httpPortBase = integer(
                        required(arguments, ++index, value), value, 1024, 65533);
                case "--server-port-base" -> serverPortBase = integer(
                        required(arguments, ++index, value), value, 1024, 65533);
                case "--target-base" -> targetBase = targetBase(
                        required(arguments, ++index, value));
                case "--operator-seed-file" -> operatorSeedFile = Path.of(
                        required(arguments, ++index, value));
                case "--payout-address" -> payoutAddress = bech32Address(
                        required(arguments, ++index, value));
                case "--address" -> address = required(arguments, ++index, value);
                case "--l2-address" -> l2Address = required(arguments, ++index, value);
                case "--l2-public-key" ->
                        l2PublicKey = required(arguments, ++index, value);
                case "--amount" -> amount = longInteger(
                        required(arguments, ++index, value), value,
                        1_000_000L, 100_000_000L);
                case "--output" ->
                        output = Path.of(required(arguments, ++index, value));
                case "--signed-transaction" ->
                        signedTransaction = Path.of(
                                required(arguments, ++index, value));
                case "--format" -> format = switch (
                        required(arguments, ++index, value)) {
                    case "text" -> EutxoDemoOptions.Format.TEXT;
                    case "json" -> EutxoDemoOptions.Format.JSON;
                    default -> throw new Usage("--format must be text or json");
                };
                case "--network" -> network = network(
                        required(arguments, ++index, value), value);
                case "--member-keys" -> memberKeys =
                        required(arguments, ++index, value);
                case "--threshold" -> threshold = integer(
                        required(arguments, ++index, value), value, 1, 32);
                case "--yes" -> confirmed = true;
                default -> {
                    if (value.startsWith("--")) {
                        throw new Usage("unknown option: " + value);
                    }
                    positional.add(value);
                }
            }
        }
        if (!help) {
            if (positional.size() != 1) {
                throw new Usage("exactly one EUTxO demo command is required");
            }
            command = positional.getFirst();
            if (!List.of("setup", "status", "reset", "scenarios",
                    "start", "up", "stop", "fund", "deposit", "transfer",
                    "prove", "settle", "withdraw", "reconcile", "verify",
                    "ceremony", "round-trip", "deposit-build",
                    "deposit-submit",
                    "settlement-bootstrap", "settlement-info",
                    "settlement-prepare", "settlement-deposit",
                    "settlement-withdraw", "settlement-status").contains(command)) {
                throw new Usage("unknown EUTxO demo command: " + command);
            }
        }
        if (scenario == null && ("setup".equals(command)
                || "scenarios".equals(command))) {
            scenario = targetBase == null ? "ledger" : "bridge";
        }
        if (!help && targetBase != null) {
            if ("setup".equals(command) && !"bridge".equals(scenario)) {
                throw new Usage("--target-base requires --scenario bridge");
            }
            if ("setup".equals(command) && operatorSeedFile == null) {
                throw new Usage("--target-base setup requires --operator-seed-file"
                        + " (the target chain's vault operator seed)");
            }
            if ("setup".equals(command) && chainId == null) {
                throw new Usage("--target-base setup requires --chain-id"
                        + " (the bridge chain id on the target cluster)");
            }
            if (List.of("start", "up", "stop").contains(command)) {
                throw new Usage("attached workspaces never manage the cluster;"
                        + " run setup once, then operations directly");
            }
        }
        // settlement-prepare is pure derivation: it needs the key and
        // nothing else, so it must not require a running node.
        if (!help && operatorSeedFile != null && targetBase == null
                && !"settlement-prepare".equals(command)) {
            throw new Usage("--operator-seed-file is valid only with"
                    + " --target-base (except settlement-prepare, which"
                    + " contacts no node)");
        }
        if (!help && payoutAddress != null
                && !("setup".equals(command) && targetBase != null)) {
            throw new Usage(
                    "--payout-address is valid only for --target-base setup");
        }
        if (chainId == null) {
            chainId = name;
        }
        return new EutxoDemoOptions(command, scenario, workspace, name, chainId,
                members, count, httpPortBase, serverPortBase,
                targetBase, operatorSeedFile, payoutAddress,
                address, l2Address, l2PublicKey, amount, output,
                signedTransaction, network, memberKeys, threshold,
                format, confirmed, help);
    }

    /**
     * The settlement actors for this invocation: the operator's own key when
     * one is supplied, otherwise the packaged devnet demo actors — which are
     * refused anywhere but devnet, because their seeds are public.
     */
    private static SettlementOperatorIdentity settlementIdentity(
            EutxoDemoOptions options, String command) {
        if (options.operatorSeedFile() != null) {
            return SettlementOperatorIdentity.fromKeyFile(
                    options.operatorSeedFile());
        }
        requireDevnetDemoActors(options, command);
        return SettlementOperatorIdentity.demo();
    }

    private static void requireDevnetDemoActors(
            EutxoDemoOptions options, String command) {
        if (!"devnet".equals(options.network())) {
            throw new IllegalArgumentException(command + " on "
                    + options.network() + " needs --operator-seed-file: the"
                    + " packaged demo actors' seeds come from a published"
                    + " formula and anyone could spend their funds"
                    + " (generate one: openssl rand -hex 32 > operator.seed)");
        }
    }

    /** Supplied threshold, else a majority of the member set. */
    private static int effectiveThreshold(EutxoDemoOptions options) {
        int supplied = options.threshold();
        return supplied > 0 ? supplied : memberKeys(options).size() / 2 + 1;
    }

    /** The chain's live federation keys, for the root datum. */
    private static java.util.List<String> memberKeys(EutxoDemoOptions options) {
        if (options.memberKeys() == null || options.memberKeys().isBlank()) {
            return ShowcaseSettlementPlan.CLUSTER_MEMBERS;
        }
        return java.util.Arrays.stream(options.memberKeys().split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .map(key -> key.toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    /** The demo actors are devnet-only; every other network needs a key file. */
    private static String network(String value, String flag) {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "devnet", "preprod", "preview", "sanchonet", "mainnet" -> normalized;
            default -> throw new Usage(flag + " must be devnet, preprod,"
                    + " preview, sanchonet, or mainnet");
        };
    }

    private static String bech32Address(String value) {
        String normalized = value.trim();
        if (!normalized.matches("addr(_test)?1[02-9ac-hj-np-z]{20,120}")) {
            throw new Usage("--payout-address must be a bech32 Cardano address");
        }
        return normalized;
    }

    private static String targetBase(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.matches("https?://[A-Za-z0-9.\\[\\]:-]+")) {
            throw new Usage("--target-base must be an http(s) URL like"
                    + " http://127.0.0.1:7070");
        }
        return normalized;
    }

    private static Path defaultWorkspace() {
        String home = System.getenv("YANO_HOME");
        Path root = home == null || home.isBlank()
                ? Path.of("").toAbsolutePath() : Path.of(home);
        return root.resolve("eutxo-demo");
    }

    private static String required(String[] arguments, int index, String option) {
        if (index >= arguments.length || arguments[index].isBlank()) {
            throw new Usage(option + " requires a value");
        }
        return arguments[index];
    }

    private static String name(String value, String option) {
        if (!value.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new Usage(option + " must be a lowercase name");
        }
        return value;
    }

    private static int integer(
            String value,
            String option,
            int minimum,
            int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new Usage(option + " is outside its supported range");
        }
    }

    private static long longInteger(
            String value,
            String option,
            long minimum,
            long maximum) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new Usage(option + " is outside its supported range");
        }
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) return "operation rejected";
        String safe = message.replaceAll("[\\r\\n\\t]", " ");
        return safe.substring(0, Math.min(safe.length(), 240));
    }

    private static final class Usage extends IllegalArgumentException {
        private Usage(String message) {
            super(message);
        }
    }
}
