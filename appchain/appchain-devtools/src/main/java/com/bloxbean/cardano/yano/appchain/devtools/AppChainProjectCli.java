package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Guided and non-interactive project initialization/render commands. */
final class AppChainProjectCli {
    static final String USAGE = """
            Usage: ./yano.sh appchain init [options]
               or: ./yano.sh appchain render [project-directory] [--format text|json]
               or: ./yano.sh appchain recipes [--format text|json]
               or: ./yano.sh appchain capabilities [--format text|json]
               or: ./yano.sh appchain doctor [project-directory] [--distribution <path>]
               or: ./yano.sh appchain diff <old.lock> <new.lock> [--format text|json]
               or: ./yano.sh appchain drift [project-directory] --peer <url> [--peer <url> ...]
               or: ./yano.sh appchain gitops [project-directory] --target helm|kustomize --output <empty-dir>
               or: ./yano.sh appchain plugin inspect|validate|sign|scaffold [options]
               or: ./yano.sh appchain metadata verify <plugin.jar> --trust-key <key-id=64-hex-public-key>
               or: ./yano.sh appchain migrate [project-directory] [--dry-run]
            Init options:
              --recipe <id>                 use `appchain recipes` to list release recipes
              --network <network>           devnet, preview, preprod, mainnet
              --members <1..32>             member count
              --member-key <64-hex>         repeat once per known public member
              --node-host <hostname>        repeat for a multi-machine host deployment
              --finality <policy>           majority, two-thirds, all
              --sequencing <mode>           fixed or rotating
              --membership <mode>           static or governed
              --runtime <type>              jvm or native
              --deployment <target>         host or docker-compose
              --http-port-base <port>        same-machine HTTP base (default 8080)
              --server-port-base <port>      same-machine n2n base (default 13337)
              --capability <id>             repeatable additive capability
              --answer <name=value>         repeatable non-secret recipe input
              --name <project-name>         safe project identifier
              --chain-id <id>               application chain identifier
              --yano-version <version>      release pin (defaults to this tool)
              --output <directory>          generated project directory
              --non-interactive             fail instead of prompting for core intent
              --format text|json            stable command result
              --plugin-jar <path>           signed custom plugin product metadata (repeatable)
              --component-catalog <path>    exported signed catalog snapshot (repeatable)
              --trust-key <id=public-key>   trusted Ed25519 publisher key (repeatable)
            Drift options:
              --peer <http(s)-base-url>     repeat for every node to compare
              --api-key-env <variable>      read the privileged API key from this environment variable
            GitOps options:
              --target <helm|kustomize>     deterministic derivative deployment format
              --output <directory>          missing or empty destination directory
            Metadata verification options:
              --trust-key <id=public-key>   repeatable trusted Ed25519 raw public key
            Plugin catalog examples:
              plugin scaffold --mode <state-machine|composite-role|effect-executor|sink>
                --id <id> --package <java-package> --yano-version <version> --output <empty-dir>
              plugin sign --catalog <json> --runtime-manifest <json> [--config-metadata <json>]
                --seed-file <file> --key-id <id> --output <signature-json>
              plugin inspect <jar|snapshot> --trust-key <id=public-key>
              plugin validate <jar|snapshot> --trust-key <id=public-key> [--output <snapshot>]
            """.stripTrailing();

    private final BufferedReader input;
    private final PrintWriter out;
    private final ObjectMapper json;
    private final AppChainPropertyRegistry properties;
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
        this.properties = java.util.Objects.requireNonNull(properties, "properties");
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
            case "drift" -> drift(parseDrift(remaining));
            case "gitops" -> gitOps(parseGitOps(remaining));
            case "plugin" -> plugin(parsePlugin(remaining));
            case "metadata" -> metadata(parseMetadata(remaining));
            case "migrate" -> migrate(parseMigrate(remaining));
            default -> throw new Usage("Unknown appchain project command: " + safe(command));
        };
    }

    private int doctor(DoctorOptions options) throws IOException {
        AppChainProjectLifecycle selected = options.project() == null ? lifecycle
                : AppChainProjectLifecycle.forProject(properties, options.project());
        if (options.project() != null) {
            verifyExplicitCatalogs(options.project(), options.pluginJars(),
                    options.componentCatalogs(), options.trustKeys());
        }
        AppChainProjectModel.DoctorReport report = selected.doctor(
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
        String status = AppChainProjectLifecycle.forProject(properties, options.project())
                .migrate(options.project(), options.dryRun());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(Map.of("status", status,
                    "schemaVersion", "v1alpha1")));
        } else {
            out.println(status + " schema=v1alpha1");
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int drift(DriftOptions options) throws IOException {
        String apiKey = null;
        if (options.apiKeyEnvironment() != null) {
            apiKey = System.getenv(options.apiKeyEnvironment());
            if (apiKey == null || apiKey.isBlank()) {
                throw new Usage("--api-key-env does not name a populated environment variable");
            }
        }
        AppChainProjectModel.DriftReport report = AppChainProjectLifecycle
                .forProject(properties, options.project()).drift(
                options.project(), options.peers(), apiKey);
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(report));
        } else {
            for (AppChainProjectModel.DriftCheck check : report.checks()) {
                out.printf(Locale.ROOT, "%s\t%s\t%s%n",
                        check.status(), check.category(), check.peer());
            }
            out.printf(Locale.ROOT, "%s peers=%d%n", report.status(), report.peerCount());
        }
        return "DRIFT_DETECTED".equals(report.status())
                ? AppChainDevtoolsCli.EXIT_INVALID_CONFIG : AppChainDevtoolsCli.EXIT_OK;
    }

    private int gitOps(GitOpsOptions options) throws IOException {
        AppChainProjectModel.GitOpsResult result = AppChainProjectLifecycle
                .forProject(properties, options.project()).gitOps(
                options.project(), options.target(), options.output());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(result));
        } else {
            out.printf(Locale.ROOT, "%s target=%s files=%d output=%s%n",
                    result.status(), result.target(), result.generatedFileCount(),
                    fileName(options.output().toAbsolutePath().normalize()));
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int metadata(MetadataOptions options) throws IOException {
        AppChainProjectModel.MetadataTrustResult result =
                new AppChainMetadataTrustVerifier().verify(
                        options.artifact(), options.trustKeys());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(result));
        } else {
            out.printf(Locale.ROOT,
                    "%s bundle=%s descriptor=%s key=%s coverage=%s%n",
                    result.status(), result.bundleId(), result.descriptorId(),
                    result.keyId(), result.validationCoverage());
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int plugin(PluginOptions options) throws IOException {
        return switch (options.action()) {
            case "inspect", "validate" -> inspectPlugin(options);
            case "sign" -> signPlugin(options);
            case "scaffold" -> scaffoldPlugin(options);
            default -> throw new Usage("Unknown plugin subcommand: " + safe(options.action()));
        };
    }

    private int inspectPlugin(PluginOptions options) throws IOException {
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        AppChainComponentCatalogLoader.Loaded loaded = isPluginArchive(options.input())
                ? loader.loadJar(options.input(), options.trustKeys())
                : loader.loadSnapshot(options.input(), options.trustKeys());
        // Applying the product catalog enforces graph, namespace, tier, and native-claim rules.
        AppChainPropertyRegistry extended = loader.extendRegistry(properties, List.of(loaded));
        new AppChainProjectCatalog(extended, List.of(loaded));
        if (options.output() != null) {
            writeNewFile(options.output(), loaded.snapshotBytes(), "catalog snapshot output");
        }
        if (options.format() == Format.JSON) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "PLUGIN_CATALOG_VALID");
            result.put("catalogId", loaded.catalog().catalogId());
            result.put("bundleId", loaded.catalog().bundleId());
            result.put("bundleVersion", loaded.catalog().bundleVersion());
            result.put("artifact", loaded.catalog().artifact());
            result.put("capabilities", loaded.catalog().capabilities());
            result.put("publisherKeyId", loaded.trust().keyId());
            result.put("catalogDigest", loaded.catalogSha256());
            result.put("artifactDigest", loaded.snapshot().artifactSha256());
            result.put("snapshotOutput", options.output() == null
                    ? null : fileName(options.output().toAbsolutePath().normalize()));
            out.println(json.writeValueAsString(result));
        } else {
            out.printf(Locale.ROOT,
                    "PLUGIN_CATALOG_VALID catalog=%s bundle=%s version=%s key=%s%n",
                    loaded.catalog().catalogId(), loaded.catalog().bundleId(),
                    loaded.catalog().bundleVersion(), loaded.trust().keyId());
            if ("inspect".equals(options.action())) {
                for (AppChainProjectModel.Capability capability
                        : loaded.catalog().capabilities()) {
                    out.printf(Locale.ROOT, "%s\t%s\t%s\t%s%n",
                            capability.id(), capability.category(), capability.availability(),
                            capability.description());
                }
            }
            if (options.output() != null) {
                out.println("SNAPSHOT_WRITTEN "
                        + fileName(options.output().toAbsolutePath().normalize()));
            }
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int signPlugin(PluginOptions options) throws IOException {
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        AppChainComponentCatalogLoader.SignedCatalog signed = loader.sign(
                options.catalog(), options.runtimeManifest(), options.configurationMetadata(),
                options.keyId(), options.seedFile());
        loader.writeSignature(options.output(), signed);
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(Map.of(
                    "status", "PLUGIN_CATALOG_SIGNED",
                    "publisherKeyId", options.keyId(),
                    "publisherPublicKey", signed.publicKeyHex(),
                    "output", fileName(options.output().toAbsolutePath().normalize()))));
        } else {
            out.printf(Locale.ROOT, "PLUGIN_CATALOG_SIGNED key=%s public-key=%s output=%s%n",
                    options.keyId(), signed.publicKeyHex(),
                    fileName(options.output().toAbsolutePath().normalize()));
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int scaffoldPlugin(PluginOptions options) throws IOException {
        AppChainPluginScaffolder.Result result = new AppChainPluginScaffolder().scaffold(
                options.mode(), options.componentId(), options.packageName(),
                valueOr(options.yanoVersion(), toolVersion()), options.output());
        if (options.format() == Format.JSON) {
            out.println(json.writeValueAsString(Map.of(
                    "status", "PLUGIN_SCAFFOLDED",
                    "mode", result.mode(),
                    "componentId", result.componentId(),
                    "catalogId", result.catalogId(),
                    "output", fileName(result.output()))));
        } else {
            out.printf(Locale.ROOT, "PLUGIN_SCAFFOLDED mode=%s id=%s output=%s%n",
                    result.mode(), result.componentId(), fileName(result.output()));
        }
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int initialize(InitOptions requested) throws IOException {
        InitOptions options = complete(requested);
        ExplicitCatalogs external = loadExplicitCatalogs(
                options.pluginJars(), options.componentCatalogs(), options.trustKeys());
        AppChainPropertyRegistry selectedProperties = external.loaded().isEmpty() ? properties
                : new AppChainComponentCatalogLoader().extendRegistry(
                properties, external.loaded());
        AppChainProjectCatalog selectedCatalog = external.loaded().isEmpty() ? catalog
                : new AppChainProjectCatalog(selectedProperties, external.loaded());
        AppChainProjectRenderer selectedRenderer = external.loaded().isEmpty() ? renderer
                : new AppChainProjectRenderer(selectedCatalog,
                new AppChainProjectResolver(selectedProperties, selectedCatalog));
        String projectName = valueOr(options.name(), slug(options.chainId()));
        String chainId = valueOr(options.chainId(), projectName);
        Path output = options.output() == null ? Path.of(projectName) : options.output();
        AppChainProjectModel.Topology topology = new AppChainProjectModel.Topology(
                options.members(), options.memberKeys(), options.nodeHosts(),
                options.finality(), options.sequencing(), options.membership(),
                options.httpPortBase(), options.serverPortBase());
        AppChainProjectModel.ChainIntent chain = new AppChainProjectModel.ChainIntent(
                chainId, options.recipe(), options.capabilities(), options.answers(), topology);
        AppChainProjectModel.Spec spec = new AppChainProjectModel.Spec(
                valueOr(options.yanoVersion(), toolVersion()),
                options.network(),
                new AppChainProjectModel.RuntimeSelection(options.runtime()),
                new AppChainProjectModel.DeploymentSelection(options.deployment()),
                List.of(chain),
                external.references());
        AppChainProjectModel.Blueprint blueprint = new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata(projectName),
                spec);
        AppChainProjectModel.Lock lock = selectedRenderer.initialize(
                output, blueprint, external.snapshotInputs());
        writeResult(options.format(), "PROJECT_INITIALIZED", output, lock);
        return AppChainDevtoolsCli.EXIT_OK;
    }

    private int render(RenderOptions options) throws IOException {
        AppChainProjectLifecycle selected = AppChainProjectLifecycle.forProject(
                properties, options.project());
        verifyExplicitCatalogs(options.project(), options.pluginJars(),
                options.componentCatalogs(), options.trustKeys());
        AppChainProjectModel.Lock lock = selected.render(options.project());
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
                out.printf(Locale.ROOT, "%s\t%s\t%s\t%s\t%s%n",
                        recipe.id(), recipe.category(), recipe.availability(),
                        recipe.maturity(), recipe.description());
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
                out.printf(Locale.ROOT, "%s\t%s\t%s\t%s\t%s\t%s\t%s%n",
                        capability.id(), capability.category(), capability.availability(),
                        capability.maturity(), capability.effectiveScope(),
                        capability.effectiveSelectable() ? "selectable" : "derived",
                        capability.description());
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
                valueOr(options.membership(), "static"),
                valueOr(options.runtime(), "jvm"),
                valueOr(options.deployment(), "host"),
                options.capabilities(),
                options.answers(),
                options.httpPortBase(),
                options.serverPortBase(),
                options.name(),
                options.chainId(),
                options.yanoVersion(),
                options.output(),
                options.nonInteractive(),
                options.format(),
                options.pluginJars(),
                options.componentCatalogs(),
                options.trustKeys());
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
        String membership = null;
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
        List<Path> pluginJars = new ArrayList<>();
        List<Path> componentCatalogs = new ArrayList<>();
        Map<String, String> trustKeys = new LinkedHashMap<>();
        Integer httpPortBase = null;
        Integer serverPortBase = null;
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
                case "--membership" -> membership = once(membership,
                        value(arguments, ++cursor, argument), argument);
                case "--runtime" -> runtime = once(runtime, value(arguments, ++cursor, argument), argument);
                case "--deployment" -> deployment = once(deployment, value(arguments, ++cursor, argument), argument);
                case "--capability" -> capabilities.add(value(arguments, ++cursor, argument));
                case "--answer" -> parseAnswer(value(arguments, ++cursor, argument), answers);
                case "--plugin-jar" -> pluginJars.add(
                        path(value(arguments, ++cursor, argument)));
                case "--component-catalog" -> componentCatalogs.add(
                        path(value(arguments, ++cursor, argument)));
                case "--trust-key" -> parseTrustKey(
                        value(arguments, ++cursor, argument), trustKeys);
                case "--http-port-base" -> {
                    if (httpPortBase != null) throw new Usage(argument + " may be specified once");
                    httpPortBase = parsePort(value(arguments, ++cursor, argument), argument);
                }
                case "--server-port-base" -> {
                    if (serverPortBase != null) throw new Usage(argument + " may be specified once");
                    serverPortBase = parsePort(value(arguments, ++cursor, argument), argument);
                }
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
                finality, sequencing, membership, runtime, deployment,
                List.copyOf(capabilities),
                Map.copyOf(answers),
                httpPortBase, serverPortBase,
                name, chainId, yanoVersion, output, nonInteractive, format,
                List.copyOf(pluginJars), List.copyOf(componentCatalogs),
                Map.copyOf(trustKeys));
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

    private static void parseTrustKey(String assignment, Map<String, String> trustKeys) {
        int separator = assignment.indexOf('=');
        if (separator < 1 || separator == assignment.length() - 1) {
            throw new Usage("--trust-key must be key-id=64-hex-public-key");
        }
        String id = assignment.substring(0, separator);
        String key = assignment.substring(separator + 1);
        if (trustKeys.putIfAbsent(id, key) != null) {
            throw new Usage("--trust-key id may be specified once: " + safe(id));
        }
        if (trustKeys.size() > 32) {
            throw new Usage("at most 32 publisher trust keys are accepted");
        }
    }

    private static RenderOptions parseRender(String[] arguments) {
        Path project = Path.of(".");
        boolean projectSeen = false;
        Format format = Format.TEXT;
        List<Path> pluginJars = new ArrayList<>();
        List<Path> componentCatalogs = new ArrayList<>();
        Map<String, String> trustKeys = new LinkedHashMap<>();
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if ("--plugin-jar".equals(argument)) {
                pluginJars.add(path(value(arguments, ++cursor, argument)));
            } else if ("--component-catalog".equals(argument)) {
                componentCatalogs.add(path(value(arguments, ++cursor, argument)));
            } else if ("--trust-key".equals(argument)) {
                parseTrustKey(value(arguments, ++cursor, argument), trustKeys);
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown render option: " + safe(argument));
            } else if (projectSeen) {
                throw new Usage("render accepts one project directory");
            } else {
                project = path(argument);
                projectSeen = true;
            }
        }
        return new RenderOptions(project, format, List.copyOf(pluginJars),
                List.copyOf(componentCatalogs), Map.copyOf(trustKeys));
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
        List<Path> pluginJars = new ArrayList<>();
        List<Path> componentCatalogs = new ArrayList<>();
        Map<String, String> trustKeys = new LinkedHashMap<>();
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--distribution".equals(argument)) {
                if (distribution != null) throw new Usage("--distribution may be specified once");
                distribution = path(value(arguments, ++cursor, argument));
            } else if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if ("--plugin-jar".equals(argument)) {
                pluginJars.add(path(value(arguments, ++cursor, argument)));
            } else if ("--component-catalog".equals(argument)) {
                componentCatalogs.add(path(value(arguments, ++cursor, argument)));
            } else if ("--trust-key".equals(argument)) {
                parseTrustKey(value(arguments, ++cursor, argument), trustKeys);
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown doctor option: " + safe(argument));
            } else if (project != null) {
                throw new Usage("doctor accepts one project directory");
            } else {
                project = path(argument);
            }
        }
        return new DoctorOptions(project, distribution, format,
                List.copyOf(pluginJars), List.copyOf(componentCatalogs),
                Map.copyOf(trustKeys));
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

    private static DriftOptions parseDrift(String[] arguments) {
        Path project = Path.of(".");
        boolean projectSeen = false;
        List<URI> peers = new ArrayList<>();
        String apiKeyEnvironment = null;
        Format format = Format.TEXT;
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--peer".equals(argument)) {
                peers.add(uri(value(arguments, ++cursor, argument)));
                if (peers.size() > 64) {
                    throw new Usage("drift accepts at most 64 peers");
                }
            } else if ("--api-key-env".equals(argument)) {
                apiKeyEnvironment = once(apiKeyEnvironment,
                        value(arguments, ++cursor, argument), argument);
                if (!apiKeyEnvironment.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new Usage("--api-key-env must be an environment variable name");
                }
            } else if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown drift option: " + safe(argument));
            } else if (projectSeen) {
                throw new Usage("drift accepts one project directory");
            } else {
                project = path(argument);
                projectSeen = true;
            }
        }
        if (peers.isEmpty()) throw new Usage("drift requires at least one --peer");
        return new DriftOptions(project, List.copyOf(peers), apiKeyEnvironment, format);
    }

    private static GitOpsOptions parseGitOps(String[] arguments) {
        Path project = Path.of(".");
        boolean projectSeen = false;
        AppChainGitOpsExporter.Target target = null;
        Path output = null;
        Format format = Format.TEXT;
        for (int cursor = 0; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--target".equals(argument)) {
                if (target != null) throw new Usage("--target may be specified once");
                target = AppChainGitOpsExporter.Target.parse(
                        value(arguments, ++cursor, argument));
            } else if ("--output".equals(argument)) {
                if (output != null) throw new Usage("--output may be specified once");
                output = path(value(arguments, ++cursor, argument));
            } else if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown gitops option: " + safe(argument));
            } else if (projectSeen) {
                throw new Usage("gitops accepts one project directory");
            } else {
                project = path(argument);
                projectSeen = true;
            }
        }
        if (target == null || output == null) {
            throw new Usage("gitops requires --target and --output");
        }
        return new GitOpsOptions(project, target, output, format);
    }

    private static PluginOptions parsePlugin(String[] arguments) {
        if (arguments.length == 0) {
            throw new Usage("plugin requires inspect, validate, sign, or scaffold");
        }
        String action = arguments[0];
        if (!Set.of("inspect", "validate", "sign", "scaffold").contains(action)) {
            throw new Usage("plugin requires inspect, validate, sign, or scaffold");
        }
        Path input = null;
        Path output = null;
        Path catalog = null;
        Path runtimeManifest = null;
        Path configurationMetadata = null;
        Path seedFile = null;
        String keyId = null;
        String mode = null;
        String componentId = null;
        String packageName = null;
        String yanoVersion = null;
        Format format = Format.TEXT;
        Map<String, String> trustKeys = new LinkedHashMap<>();
        for (int cursor = 1; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            switch (argument) {
                case "--output" -> {
                    if (output != null) throw new Usage("--output may be specified once");
                    output = path(value(arguments, ++cursor, argument));
                }
                case "--trust-key" -> parseTrustKey(
                        value(arguments, ++cursor, argument), trustKeys);
                case "--catalog" -> {
                    if (catalog != null) throw new Usage("--catalog may be specified once");
                    catalog = path(value(arguments, ++cursor, argument));
                }
                case "--runtime-manifest" -> {
                    if (runtimeManifest != null) {
                        throw new Usage("--runtime-manifest may be specified once");
                    }
                    runtimeManifest = path(value(arguments, ++cursor, argument));
                }
                case "--config-metadata" -> {
                    if (configurationMetadata != null) {
                        throw new Usage("--config-metadata may be specified once");
                    }
                    configurationMetadata = path(value(arguments, ++cursor, argument));
                }
                case "--seed-file" -> {
                    if (seedFile != null) throw new Usage("--seed-file may be specified once");
                    seedFile = path(value(arguments, ++cursor, argument));
                }
                case "--key-id" -> keyId = once(
                        keyId, value(arguments, ++cursor, argument), argument);
                case "--mode" -> mode = once(
                        mode, value(arguments, ++cursor, argument), argument);
                case "--id" -> componentId = once(
                        componentId, value(arguments, ++cursor, argument), argument);
                case "--package" -> packageName = once(
                        packageName, value(arguments, ++cursor, argument), argument);
                case "--yano-version" -> yanoVersion = once(
                        yanoVersion, value(arguments, ++cursor, argument), argument);
                case "--format" -> format = parseFormat(
                        value(arguments, ++cursor, argument));
                default -> {
                    if (argument.startsWith("--")) {
                        throw new Usage("Unknown plugin option: " + safe(argument));
                    }
                    if (input != null) {
                        throw new Usage("plugin subcommand accepts one positional input");
                    }
                    input = path(argument);
                }
            }
        }
        if (Set.of("inspect", "validate").contains(action)) {
            if (input == null || trustKeys.isEmpty()) {
                throw new Usage(action + " requires an artifact/snapshot and --trust-key");
            }
            if (catalog != null || runtimeManifest != null || seedFile != null || keyId != null
                    || mode != null || componentId != null || packageName != null) {
                throw new Usage("sign/scaffold options are invalid for " + action);
            }
        } else if ("sign".equals(action)) {
            if (input != null || catalog == null || runtimeManifest == null || seedFile == null
                    || keyId == null || output == null || !trustKeys.isEmpty()) {
                throw new Usage("sign requires --catalog, --runtime-manifest, --seed-file, "
                        + "--key-id, and --output");
            }
        } else if (input != null || mode == null || componentId == null
                || packageName == null || output == null || !trustKeys.isEmpty()
                || catalog != null || runtimeManifest != null || seedFile != null
                || keyId != null || configurationMetadata != null) {
            throw new Usage("scaffold requires --mode, --id, --package, and --output");
        }
        return new PluginOptions(action, input, output, Map.copyOf(trustKeys),
                catalog, runtimeManifest, configurationMetadata, seedFile, keyId,
                mode, componentId, packageName, yanoVersion, format);
    }

    private static MetadataOptions parseMetadata(String[] arguments) {
        if (arguments.length == 0 || !"verify".equals(arguments[0])) {
            throw new Usage("metadata requires the verify subcommand");
        }
        Path artifact = null;
        Map<String, String> trustKeys = new LinkedHashMap<>();
        Format format = Format.TEXT;
        for (int cursor = 1; cursor < arguments.length; cursor++) {
            String argument = arguments[cursor];
            if ("--trust-key".equals(argument)) {
                parseTrustKey(value(arguments, ++cursor, argument), trustKeys);
            } else if ("--format".equals(argument)) {
                format = parseFormat(value(arguments, ++cursor, argument));
            } else if (argument.startsWith("--")) {
                throw new Usage("Unknown metadata verify option: " + safe(argument));
            } else if (artifact != null) {
                throw new Usage("metadata verify accepts one plugin artifact");
            } else {
                artifact = path(argument);
            }
        }
        if (artifact == null || trustKeys.isEmpty()) {
            throw new Usage("metadata verify requires a plugin artifact and --trust-key");
        }
        return new MetadataOptions(artifact, Map.copyOf(trustKeys), format);
    }

    private ExplicitCatalogs loadExplicitCatalogs(
            List<Path> pluginJars,
            List<Path> componentCatalogs,
            Map<String, String> trustKeys) throws IOException {
        List<Path> jars = pluginJars == null ? List.of() : pluginJars;
        List<Path> snapshots = componentCatalogs == null ? List.of() : componentCatalogs;
        Map<String, String> keys = trustKeys == null ? Map.of() : trustKeys;
        if (jars.isEmpty() && snapshots.isEmpty()) {
            if (!keys.isEmpty()) throw new Usage("--trust-key requires a custom catalog input");
            return new ExplicitCatalogs(List.of(), List.of(), Map.of());
        }
        if (jars.size() + snapshots.size() > AppChainComponentCatalogLoader.MAX_CATALOGS) {
            throw new Usage("at most 16 custom component catalogs are accepted");
        }
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        List<AppChainComponentCatalogLoader.Loaded> loaded = new ArrayList<>();
        for (Path jar : jars) loaded.add(loader.loadJar(jar, keys));
        for (Path snapshot : snapshots) loaded.add(loader.loadSnapshot(snapshot, keys));
        loaded.sort(java.util.Comparator.comparing(item -> item.catalog().catalogId()));

        List<AppChainProjectModel.ComponentCatalogRef> references = new ArrayList<>();
        Map<String, byte[]> inputs = new LinkedHashMap<>();
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (AppChainComponentCatalogLoader.Loaded item : loaded) {
            if (!ids.add(item.catalog().catalogId())) {
                throw new IllegalArgumentException("custom component catalog id collides");
            }
            String publicKey = keys.get(item.trust().keyId());
            if (publicKey == null) {
                throw new IllegalArgumentException("missing trusted publisher key for catalog");
            }
            references.add(new AppChainProjectModel.ComponentCatalogRef(
                    item.inputPath(), item.trust().keyId(), publicKey.toLowerCase(Locale.ROOT)));
            inputs.put(item.inputPath(), item.snapshotBytes());
        }
        return new ExplicitCatalogs(List.copyOf(loaded), List.copyOf(references),
                Map.copyOf(inputs));
    }

    private void verifyExplicitCatalogs(
            Path project,
            List<Path> pluginJars,
            List<Path> componentCatalogs,
            Map<String, String> trustKeys) throws IOException {
        ExplicitCatalogs explicit = loadExplicitCatalogs(
                pluginJars, componentCatalogs, trustKeys);
        if (explicit.loaded().isEmpty()) return;
        Map<String, AppChainComponentCatalogLoader.Loaded> expected = new LinkedHashMap<>();
        for (AppChainComponentCatalogLoader.Loaded item
                : new AppChainComponentCatalogLoader().loadProject(project)) {
            expected.put(item.catalog().catalogId(), item);
        }
        for (AppChainComponentCatalogLoader.Loaded item : explicit.loaded()) {
            AppChainComponentCatalogLoader.Loaded pinned = expected.get(item.catalog().catalogId());
            if (pinned == null
                    || !pinned.catalogSha256().equals(item.catalogSha256())
                    || !pinned.runtimeManifestSha256().equals(item.runtimeManifestSha256())
                    || !pinned.snapshot().artifactSha256()
                    .equals(item.snapshot().artifactSha256())) {
                throw new IllegalArgumentException(
                        "explicit component input does not match the project pin: "
                                + item.catalog().catalogId());
            }
        }
    }

    private static URI uri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException failure) {
            throw new Usage("--peer URL is invalid");
        }
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

    private static int parsePort(String value, String option) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1024 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException failure) {
            throw new Usage(option + " must be an integer in [1024, 65535]");
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

    private static boolean isPluginArchive(Path path) {
        String file = path.getFileName() == null ? ""
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return file.endsWith(".jar") || file.endsWith(".zip");
    }

    private static void writeNewFile(Path output, byte[] bytes, String label) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " already exists");
        }
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
            String membership,
            String runtime,
            String deployment,
            List<String> capabilities,
            Map<String, String> answers,
            Integer httpPortBase,
            Integer serverPortBase,
            String name,
            String chainId,
            String yanoVersion,
            Path output,
            boolean nonInteractive,
            Format format,
            List<Path> pluginJars,
            List<Path> componentCatalogs,
            Map<String, String> trustKeys) {
        int members() {
            return membersBoxed;
        }
    }

    record RenderOptions(
            Path project,
            Format format,
            List<Path> pluginJars,
            List<Path> componentCatalogs,
            Map<String, String> trustKeys) {
    }

    record DoctorOptions(
            Path project,
            Path distribution,
            Format format,
            List<Path> pluginJars,
            List<Path> componentCatalogs,
            Map<String, String> trustKeys) {
    }

    record DiffOptions(Path before, Path after, Format format) {
    }

    record DriftOptions(
            Path project,
            List<URI> peers,
            String apiKeyEnvironment,
            Format format) {
    }

    record MigrateOptions(Path project, boolean dryRun, Format format) {
    }

    record GitOpsOptions(
            Path project,
            AppChainGitOpsExporter.Target target,
            Path output,
            Format format) {
    }

    record MetadataOptions(
            Path artifact,
            Map<String, String> trustKeys,
            Format format) {
    }

    record PluginOptions(
            String action,
            Path input,
            Path output,
            Map<String, String> trustKeys,
            Path catalog,
            Path runtimeManifest,
            Path configurationMetadata,
            Path seedFile,
            String keyId,
            String mode,
            String componentId,
            String packageName,
            String yanoVersion,
            Format format) {
    }

    record ExplicitCatalogs(
            List<AppChainComponentCatalogLoader.Loaded> loaded,
            List<AppChainProjectModel.ComponentCatalogRef> references,
            Map<String, byte[]> snapshotInputs) {
    }

    static final class Usage extends IllegalArgumentException {
        Usage(String message) {
            super(message);
        }
    }
}
