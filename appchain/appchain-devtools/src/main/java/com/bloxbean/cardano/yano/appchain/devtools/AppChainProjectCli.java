package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Guided and non-interactive project initialization/render commands. */
final class AppChainProjectCli {
    static final String USAGE = """
            Usage: yano appchain init [options]
               or: yano appchain render [project-directory] [--format text|json]
               or: yano appchain recipes [--format text|json]
               or: yano appchain capabilities [--format text|json]
               or: yano appchain doctor [project-directory] [--distribution <path>]
               or: yano appchain diff <old.lock> <new.lock> [--format text|json]
               or: yano appchain migrate [project-directory] [--dry-run]
            Init options:
              --recipe <id>                 audit-log, owned-registry, evidence-publication
              --network <network>           devnet, preview, preprod, mainnet
              --members <1..32>             member count
              --member-key <64-hex>         repeat once per known public member
              --node-host <hostname>        repeat for a multi-machine host deployment
              --finality <policy>           majority, two-thirds, all
              --sequencing <mode>           fixed or rotating
              --runtime <type>              jvm or native
              --deployment <target>         host or docker-compose
              --capability <id>             repeatable additive capability
              --answer <name=value>         repeatable non-secret recipe input
              --name <project-name>         safe project identifier
              --chain-id <id>               application chain identifier
              --yano-version <version>      release pin (defaults to this tool)
              --output <directory>          generated project directory
              --non-interactive             fail instead of prompting for core intent
              --format text|json            stable command result
            """.stripTrailing();

    private final BufferedReader input;
    private final PrintWriter out;
    private final ObjectMapper json;
    private final AppChainProjectCatalog catalog;
    private final AppChainProjectRenderer renderer;
    private final AppChainProjectLifecycle lifecycle;

    AppChainProjectCli(PrintWriter out) throws IOException {
        this(new BufferedReader(new InputStreamReader(System.in)), out,
                builtInRegistry(), new ObjectMapper());
    }

    private static AppChainPropertyRegistry builtInRegistry() throws IOException {
        List<com.bloxbean.cardano.yano.appchain.config.AppChainMetadataSource> sources =
                new AppChainDescriptorLoader().loadBuiltInMetadata().stream()
                        .map(com.bloxbean.cardano.yano.appchain.config.AppChainMetadataDescriptor::toSource)
                        .toList();
        return AppChainPropertyRegistry.withSources(sources);
    }

    AppChainProjectCli(
            BufferedReader input,
            PrintWriter out,
            AppChainPropertyRegistry properties,
            ObjectMapper json) throws IOException {
        this.input = java.util.Objects.requireNonNull(input, "input");
        this.out = java.util.Objects.requireNonNull(out, "out");
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.catalog = new AppChainProjectCatalog(properties);
        this.renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        this.lifecycle = new AppChainProjectLifecycle(properties);
    }

    int run(String[] arguments) throws IOException {
        int cursor = 0;
        if (cursor < arguments.length && "appchain".equals(arguments[cursor])) cursor++;
        if (cursor >= arguments.length) throw new Usage("An appchain command is required");
        String command = arguments[cursor++];
        String[] remaining = java.util.Arrays.copyOfRange(arguments, cursor, arguments.length);
        if (helpRequested(remaining)) {
            out.println(USAGE);
            return AppChainDevtoolsCli.EXIT_OK;
        }
        return switch (command) {
            case "init" -> initialize(parseInit(remaining));
            case "render" -> render(parseRender(remaining));
            case "recipes" -> recipes(parseFormatOnly(remaining));
            case "capabilities" -> capabilities(parseFormatOnly(remaining));
            case "doctor" -> doctor(parseDoctor(remaining));
            case "diff" -> diff(parseDiff(remaining));
            case "migrate" -> migrate(parseMigrate(remaining));
            default -> throw new Usage("Unknown appchain project command: " + safe(command));
        };
    }

    private int doctor(DoctorOptions options) throws IOException {
        AppChainProjectModel.DoctorReport report = lifecycle.doctor(
                options.project(), options.distribution());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(report));
        } else {
            for (AppChainProjectModel.DoctorCheck check : report.checks()) {
                out.printf(Locale.ROOT, "%s\t%s\t%s%n",
                        check.status(), check.id(), check.detail());
            }
            out.println(report.status());
        }
        return "DOCTOR_FAILED".equals(report.status())
                ? AppChainDevtoolsCli.EXIT_INVALID_CONFIG : AppChainDevtoolsCli.EXIT_OK;
    }

    private int diff(DiffOptions options) throws IOException {
        AppChainProjectModel.LockDiff result = lifecycle.diff(options.before(), options.after());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(result));
        } else {
            for (AppChainProjectModel.LockChange change : result.changes()) {
                out.printf(Locale.ROOT, "%s\t%s%n", change.policy(), change.key());
            }
            out.printf(Locale.ROOT, "%s changes=%d categories=%s%n",
                    result.status(), result.changes().size(), result.categories());
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int migrate(MigrateOptions options) throws IOException {
        String status = lifecycle.migrate(options.project(), options.dryRun());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(Map.of("status", status,
                    "schemaVersion", "v1alpha1")));
        } else {
            out.println(status + " schema=v1alpha1");
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int initialize(InitOptions requested) throws IOException {
        InitOptions options = complete(requested);
        String projectName = valueOr(options.name(), slug(options.chainId()));
        String chainId = valueOr(options.chainId(), projectName);
        Path output = options.output() == null ? Path.of(projectName) : options.output();
        AppChainProjectModel.Blueprint blueprint = new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata(projectName),
                new AppChainProjectModel.Spec(
                        valueOr(options.yanoVersion(), toolVersion()),
                        options.network(),
                        new AppChainProjectModel.RuntimeSelection(options.runtime()),
                        new AppChainProjectModel.DeploymentSelection(options.deployment()),
                        List.of(new AppChainProjectModel.ChainIntent(
                                chainId,
                                options.recipe(),
                                options.capabilities(),
                                options.answers(),
                                new AppChainProjectModel.Topology(
                                        options.members(), options.memberKeys(), options.nodeHosts(),
                                        options.finality(), options.sequencing(), "static")))));
        AppChainProjectModel.Lock lock = renderer.initialize(output, blueprint);
        writeResult(options.format(), "PROJECT_INITIALIZED", output, lock);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int render(RenderOptions options) throws IOException {
        AppChainProjectModel.Lock lock = renderer.render(options.project());
        writeResult(options.format(), "PROJECT_RENDERED", options.project(), lock);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int recipes(Format format) throws IOException {
        if (format == Format.JSON) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "RECIPE_CATALOG");
            result.put("schemaVersion", "v1alpha1");
            result.put("recipes", catalog.recipes());
            out.println(json.writeValueAsString(result));
        } else {
            for (AppChainProjectModel.Recipe recipe : catalog.recipes()) {
                out.printf(Locale.ROOT, "%s\t%s\t%s%n",
                        recipe.id(), recipe.maturity(), recipe.description());
            }
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int capabilities(Format format) throws IOException {
        if (format == Format.JSON) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "CAPABILITY_CATALOG");
            result.put("schemaVersion", "v1alpha1");
            result.put("capabilities", catalog.capabilities());
            out.println(json.writeValueAsString(result));
        } else {
            for (AppChainProjectModel.Capability capability : catalog.capabilities()) {
                out.printf(Locale.ROOT, "%s\t%s\t%s\t%s%n", capability.id(),
                        capability.category(), capability.maturity(), capability.description());
            }
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private void writeResult(
            Format format,
            String status,
            Path project,
            AppChainProjectModel.Lock lock) throws IOException {
        String safeProject = fileName(project.toAbsolutePath().normalize());
        if (format == Format.JSON) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("project", safeProject);
            result.put("recipe", lock.recipe());
            result.put("runtime", lock.runtime());
            result.put("deployment", lock.deployment());
            result.put("threshold", lock.consensusValues().get(
                    "yano.app-chain.chains[0].threshold"));
            result.put("resolvedConfigDigest", lock.resolvedConfigDigest());
            result.put("validationCoverage", lock.validationCoverage());
            result.put("maturity", lock.maturity());
            result.put("acknowledgements", lock.acknowledgements());
            out.println(json.writeValueAsString(result));
        } else {
            out.printf(Locale.ROOT,
                    "%s project=%s recipe=%s runtime=%s deployment=%s coverage=%s "
                            + "maturity=%s acknowledgements=%s%n",
                    status, safeProject, lock.recipe(), lock.runtime(), lock.deployment(),
                    lock.validationCoverage(), lock.maturity(), lock.acknowledgements());
        }
    }

    private InitOptions complete(InitOptions options) throws IOException {
        String recipe = options.recipe();
        String network = options.network();
        Integer members = options.membersBoxed();
        if (options.nonInteractive()) {
            if (recipe == null || network == null || members == null) {
                throw new Usage("--non-interactive requires --recipe, --network, and --members");
            }
        } else {
            recipe = prompt("Recipe", recipe, "audit-log");
            network = prompt("Network", network, "devnet");
            String memberText = prompt("Members", members == null ? null : members.toString(), "3");
            members = parseMembers(memberText);
        }
        catalog.recipe(recipe);
        return new InitOptions(
                recipe,
                network,
                members,
                options.memberKeys(),
                options.nodeHosts(),
                valueOr(options.finality(), "two-thirds"),
                valueOr(options.sequencing(), "fixed"),
                valueOr(options.runtime(), "jvm"),
                valueOr(options.deployment(), "host"),
                options.capabilities(),
                options.answers(),
                options.name(),
                options.chainId(),
                options.yanoVersion(),
                options.output(),
                options.nonInteractive(),
                options.format());
    }

    private String prompt(String label, String existing, String defaultValue) throws IOException {
        if (existing != null) return existing;
        out.printf("%s [%s]: ", label, defaultValue);
        out.flush();
        String value = input.readLine();
        if (value == null || value.isBlank()) return defaultValue;
        return value.trim();
    }

    private static InitOptions parseInit(String[] arguments) {
        String recipe = null;
        String network = null;
        Integer members = null;
        String finality = null;
        String sequencing = null;
        String runtime = null;
        String deployment = null;
        String name = null;
        String chainId = null;
        String yanoVersion = null;
        Path output = null;
        Format format = Format.TEXT;
        boolean nonInteractive = false;
        List<String> memberKeys = new ArrayList<>();
        List<String> nodeHosts = new ArrayList<>();
        List<String> capabilities = new ArrayList<>();
        Map<String, String> answers = new LinkedHashMap<>();
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            switch (argument) {
                case "--recipe" -> recipe = once(recipe, value(arguments, ++cursor, argument), argument);
                case "--network" -> network = once(network, value(arguments, ++cursor, argument), argument);
                case "--members" -> {
                    if (members != null) throw new Usage("--members may be specified once");
                    members = parseMembers(value(arguments, ++cursor, argument));
                }
                case "--member-key" -> memberKeys.add(value(arguments, ++cursor, argument));
                case "--node-host" -> nodeHosts.add(value(arguments, ++cursor, argument));
                case "--finality" -> finality = once(finality, value(arguments, ++cursor, argument), argument);
                case "--sequencing" -> sequencing = once(sequencing, value(arguments, ++cursor, argument), argument);
                case "--runtime" -> runtime = once(runtime, value(arguments, ++cursor, argument), argument);
                case "--deployment" -> deployment = once(deployment, value(arguments, ++cursor, argument), argument);
                case "--capability" -> capabilities.add(value(arguments, ++cursor, argument));
                case "--answer" -> parseAnswer(value(arguments, ++cursor, argument), answers);
                case "--name" -> name = once(name, value(arguments, ++cursor, argument), argument);
                case "--chain-id" -> chainId = once(chainId, value(arguments, ++cursor, argument), argument);
                case "--yano-version" -> yanoVersion = once(
                        yanoVersion, value(arguments, ++cursor, argument), argument);
                case "--output" -> {
                    if (output != null) throw new Usage("--output may be specified once");
                    output = path(value(arguments, ++cursor, argument));
                }
                case "--format" -> format = parseFormat(value(arguments, ++cursor, argument));
                case "--non-interactive" -> nonInteractive = true;
                default -> throw new Usage("Unknown init option: " + safe(argument));
            }
        }
        return new InitOptions(recipe, network, members, List.copyOf(memberKeys),
                List.copyOf(nodeHosts),
                finality, sequencing, runtime, deployment, List.copyOf(capabilities),
                Map.copyOf(answers),
                name, chainId, yanoVersion, output, nonInteractive, format);
    }

    private static void parseAnswer(String assignment, Map<String, String> answers) {
        int separator = assignment.indexOf('=');
        if (separator < 1 || separator == assignment.length() - 1) {
            throw new Usage("--answer must be name=value");
        }
        String name = assignment.substring(0, separator);
        String value = assignment.substring(separator + 1);
        if (answers.putIfAbsent(name, value) != null) {
            throw new Usage("--answer name may be specified once: " + safe(name));
        }
    }

    private static RenderOptions parseRender(String[] arguments) {
        Path project = Path.of(".");
        boolean projectSeen = false;
        Format format = Format.TEXT;
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown render option: " + safe(argument));
            } else if (projectSeen) {
                throw new Usage("render accepts one project directory");
            } else {
                project = path(argument);
                projectSeen = true;
            }
        }
        return new RenderOptions(project, format);
    }

    private static Format parseFormatOnly(String[] arguments) {
        if (arguments.length == 0) return Format.TEXT;
        if (arguments.length == 2 && "--format".equals(arguments[0])) {
            return parseFormat(arguments[1]);
        }
        throw new Usage("recipes accepts only --format text|json");
    }

    private static DoctorOptions parseDoctor(String[] arguments) {
        Path project = null;
        Path distribution = null;
        Format format = Format.TEXT;
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--distribution".equals(argument)) {
                if (distribution != null) throw new Usage("--distribution may be specified once");
                distribution = path(value(arguments, ++cursor, argument));
            } else if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown doctor option: " + safe(argument));
            } else if (project != null) {
                throw new Usage("doctor accepts one project directory");
            } else {
                project = path(argument);
            }
        }
        return new DoctorOptions(project, distribution, format);
    }

    private static DiffOptions parseDiff(String[] arguments) {
        List<Path> locks = new ArrayList<>();
        Format format = Format.TEXT;
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown diff option: " + safe(argument));
            } else {
                locks.add(path(argument));
            }
        }
        if (locks.size() != 2) throw new Usage("diff requires old.lock and new.lock");
        return new DiffOptions(locks.get(0), locks.get(1), format);
    }

    private static MigrateOptions parseMigrate(String[] arguments) {
        Path project = Path.of(".");
        boolean projectSeen = false;
        boolean dryRun = false;
        Format format = Format.TEXT;
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--dry-run".equals(argument)) {
                dryRun = true;
            } else if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown migrate option: " + safe(argument));
            } else if (projectSeen) {
                throw new Usage("migrate accepts one project directory");
            } else {
                project = path(argument);
                projectSeen = true;
            }
        }
        return new MigrateOptions(project, dryRun, format);
    }

    private static Format parseFormat(String value) {
        return switch (value) {
            case "text" -> Format.TEXT;
            case "json" -> Format.JSON;
            default -> throw new Usage("--format must be text or json");
        };
    }

    private static int parseMembers(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 32) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException failure) {
            throw new Usage("--members must be an integer in [1, 32]");
        }
    }

    private static String once(String prior, String value, String option) {
        if (prior != null) throw new Usage(option + " may be specified once");
        return value;
    }

    private static String value(String[] arguments, int index, String option) {
        if (index >= arguments.length) throw new Usage(option + " requires a value");
        return arguments[index];
    }

    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException failure) {
            throw new Usage("Path is invalid");
        }
    }

    private static boolean helpRequested(String[] arguments) {
        for (String argument : arguments) {
            if ("help".equals(argument) || "-h".equals(argument) || "--help".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) return "yano-appchain";
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) slug = "appchain-" + slug;
        return slug.length() <= 63 ? slug : slug.substring(0, 63).replaceAll("-+$", "");
    }

    private static String toolVersion() {
        String version = AppChainProjectCli.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = System.getProperty("yano.version", "development");
        }
        return version;
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "<project>" : safe(name.toString());
    }

    private static String valueOr(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String safe(String value) {
        if (value == null) return "<missing>";
        return value.codePoints().limit(256)
                .collect(StringBuilder::new,
                        (builder, codePoint) -> builder.appendCodePoint(
                                Character.isISOControl(codePoint) ? '?' : codePoint),
                        StringBuilder::append)
                .toString();
    }

    enum Format { TEXT, JSON }

    record InitOptions(
            String recipe,
            String network,
            Integer membersBoxed,
            List<String> memberKeys,
            List<String> nodeHosts,
            String finality,
            String sequencing,
            String runtime,
            String deployment,
            List<String> capabilities,
            Map<String, String> answers,
            String name,
            String chainId,
            String yanoVersion,
            Path output,
            boolean nonInteractive,
            Format format) {
        int members() {
            return membersBoxed;
        }
    }

    record RenderOptions(Path project, Format format) {
    }

    record DoctorOptions(Path project, Path distribution, Format format) {
    }

    record DiffOptions(Path before, Path after, Format format) {
    }

    record MigrateOptions(Path project, boolean dryRun, Format format) {
    }

    static final class Usage extends IllegalArgumentException {
        Usage(String message) {
            super(message);
        }
    }
}
