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
               or: ./yano.sh appchain eutxo demo status [options]
               or: ./yano.sh appchain eutxo demo reset --yes [options]
            Options:
              --scenario ledger|bridge|zk  default ledger
              --workspace <directory>      default $YANO_HOME/eutxo-demo
              --name <project-name>        default payments-eutxo
              --chain-id <chain-id>        default project name
              --members <count>            default 3
              --http-port-base <port>      default 7070
              --server-port-base <port>    default 13337
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
        if ("setup".equals(options.command())) {
            EutxoDemoScenarioProvider provider = scenarios.require(options.scenario());
            EutxoDemoWorkspace workspace = EutxoDemoWorkspace.create(options, provider);
            return ready(workspace);
        }
        EutxoDemoWorkspace workspace = EutxoDemoWorkspace.open(options.workspace());
        if (options.scenario() != null
                && !options.scenario().equals(workspace.manifest().scenario())) {
            throw new IllegalArgumentException(
                    "requested scenario conflicts with the workspace manifest");
        }
        EutxoDemoScenarioProvider provider =
                scenarios.require(workspace.manifest().scenario());
        if ("status".equals(options.command())) {
            Map<String, Object> fields = common(workspace);
            fields.put("operations", workspace.journal().read());
            fields.put("trustBoundary", provider.trustBoundary());
            return EutxoDemoResult.of("EUTXO_DEMO_STATUS", fields);
        }
        if ("reset".equals(options.command())) {
            if (!options.confirmed()) {
                throw new Usage("reset requires --yes");
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
        return provider.execute(options.command(), workspace, options);
    }

    private static EutxoDemoResult ready(EutxoDemoWorkspace workspace) {
        Map<String, Object> fields = common(workspace);
        fields.put("network", workspace.manifest().network());
        fields.put("nodes", workspace.manifest().members());
        fields.put("ports", workspace.manifest().httpPortBase() + "-"
                + (workspace.manifest().httpPortBase()
                + workspace.manifest().members() - 1));
        fields.put("secrets", "disposable development identities");
        fields.put("next", "./yano.sh appchain eutxo demo start --workspace "
                + workspace.root());
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
        int httpPortBase = 7070;
        int serverPortBase = 13337;
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
                case "--http-port-base" -> httpPortBase = integer(
                        required(arguments, ++index, value), value, 1024, 65533);
                case "--server-port-base" -> serverPortBase = integer(
                        required(arguments, ++index, value), value, 1024, 65533);
                case "--format" -> format = switch (
                        required(arguments, ++index, value)) {
                    case "text" -> EutxoDemoOptions.Format.TEXT;
                    case "json" -> EutxoDemoOptions.Format.JSON;
                    default -> throw new Usage("--format must be text or json");
                };
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
                    "ceremony", "round-trip").contains(command)) {
                throw new Usage("unknown EUTxO demo command: " + command);
            }
        }
        if (scenario == null && ("setup".equals(command) || "scenarios".equals(command))) {
            scenario = "ledger";
        }
        if (chainId == null) {
            chainId = name;
        }
        return new EutxoDemoOptions(command, scenario, workspace, name, chainId,
                members, httpPortBase, serverPortBase, format, confirmed, help);
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
