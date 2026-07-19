package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainMetadataSource;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyDefinition;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.AppChainResolvedValidator;
import com.bloxbean.cardano.yano.appchain.config.AppChainTemplateValidator;
import com.bloxbean.cardano.yano.appchain.config.DynamicNamespaceDefinition;
import com.bloxbean.cardano.yano.appchain.config.EffectiveConfigValue;
import com.bloxbean.cardano.yano.appchain.config.ResolvedValidationResult;
import com.bloxbean.cardano.yano.appchain.config.TemplateContract;
import com.bloxbean.cardano.yano.appchain.config.TemplateValidationResult;
import com.bloxbean.cardano.yano.appchain.config.ValidationDiagnostic;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Offline {@code yano appchain config} command-line entry point. */
public final class AppChainDevtoolsCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_INVALID_CONFIG = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_IO = 74;

    private static final String USAGE = """
            Usage: yano appchain config validate --mode template [options] <config.yml>
               or: yano appchain config validate --mode resolved --config <file> [options]
               or: yano appchain config validate --mode project <project-directory>
               or: yano appchain config effective --mode resolved --config <file> [options]
               or: yano appchain config explain [options] <property>
               or: yano appchain init [options]
               or: yano appchain render [project-directory]
               or: yano appchain recipes [--format text|json]
               or: yano appchain capabilities [--format text|json]
               or: yano appchain doctor [project-directory] [--distribution <path>]
               or: yano appchain diff <old.lock> <new.lock>
               or: yano appchain migrate [project-directory] [--dry-run]
            Options:
              --config <yml|yaml|properties>             repeatable, later source wins
              --format text|json                         validate/explain
              --format yaml|json                         effective (default: yaml)
              --metadata <descriptor|plugin.jar>         repeatable
              --template-contract <file|builtin:cluster> template validate only
              --profile <name>                           resolved commands
              --include-environment                      resolved commands, default off
              --include-system-properties                resolved commands, default off
              --show-sources                             effective only
            """.stripTrailing();

    private final AppChainConfigFileLoader configLoader;
    private final AppChainDescriptorLoader descriptorLoader;
    private final AppChainResolvedConfigResolver resolver;
    private final ObjectMapper json;
    private final ObjectMapper yaml;

    public AppChainDevtoolsCli() {
        this(new AppChainConfigFileLoader(), new AppChainDescriptorLoader(), new ObjectMapper());
    }

    AppChainDevtoolsCli(
            AppChainConfigFileLoader configLoader,
            AppChainDescriptorLoader descriptorLoader,
            ObjectMapper json) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.descriptorLoader = Objects.requireNonNull(descriptorLoader, "descriptorLoader");
        this.resolver = new AppChainResolvedConfigResolver(configLoader);
        this.json = Objects.requireNonNull(json, "json");
        this.yaml = new ObjectMapper(new YAMLFactory());
    }

    /** Run without closing the supplied streams. */
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (projectCommand(args)) {
            try {
                int exit = new AppChainProjectCli(out).run(args);
                out.flush();
                return exit;
            } catch (AppChainProjectCli.Usage failure) {
                err.println(safeArgument(bounded(firstLine(failure.getMessage()))));
                err.println(AppChainProjectCli.USAGE);
                err.flush();
                return EXIT_USAGE;
            } catch (IOException failure) {
                err.println("App-chain project could not be read or written: "
                        + safeArgument(bounded(firstLine(failure.getMessage()))));
                err.flush();
                return EXIT_IO;
            } catch (IllegalArgumentException | IllegalStateException failure) {
                err.println("App-chain project is invalid: "
                        + safeArgument(bounded(firstLine(failure.getMessage()))));
                err.flush();
                return EXIT_INVALID_CONFIG;
            }
        }
        if (helpRequested(args)) {
            out.println(USAGE);
            out.flush();
            return EXIT_OK;
        }

        ParsedArguments parsed;
        try {
            parsed = parse(args);
        } catch (UsageException failure) {
            err.println(failure.getMessage());
            err.println(USAGE);
            err.flush();
            return EXIT_USAGE;
        }

        try {
            AppChainPropertyRegistry registry = registry(parsed.metadata());
            int exit = switch (parsed.command()) {
                case VALIDATE -> validate(parsed, registry, out);
                case EFFECTIVE -> effective(parsed, registry, out);
                case EXPLAIN -> explain(parsed, registry, out);
            };
            out.flush();
            return exit;
        } catch (IOException failure) {
            err.println("App-chain metadata/configuration could not be read: "
                    + safeDiagnostic(failure, parsed));
            err.flush();
            return EXIT_IO;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            err.println("App-chain metadata/configuration is invalid: "
                    + safeArgument(bounded(firstLine(failure.getMessage()))));
            err.flush();
            return EXIT_INVALID_CONFIG;
        }
    }

    public static void main(String[] args) {
        int exit = new AppChainDevtoolsCli().run(
                args, new PrintWriter(System.out), new PrintWriter(System.err));
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    private AppChainPropertyRegistry registry(List<Path> descriptors) throws IOException {
        List<AppChainMetadataSource> sources = new ArrayList<>();
        descriptorLoader.loadBuiltInMetadata().stream()
                .map(com.bloxbean.cardano.yano.appchain.config.AppChainMetadataDescriptor::toSource)
                .forEach(sources::add);
        for (Path descriptor : descriptors) {
            sources.add(descriptorLoader.loadMetadata(descriptor).toSource());
        }
        return AppChainPropertyRegistry.withSources(sources);
    }

    private int validate(
            ParsedArguments parsed,
            AppChainPropertyRegistry registry,
            PrintWriter out) throws IOException {
        if (parsed.mode() == Mode.TEMPLATE) {
            return validateTemplate(parsed, registry, out);
        }
        if (parsed.mode() == Mode.PROJECT) {
            AppChainProjectModel.ProjectValidation result =
                    new AppChainProjectLifecycle(registry).validate(parsed.targetPath());
            if (parsed.format() == Format.JSON) {
                Map<String, Object> output = validationEnvelope(
                        "VALID_PROJECT", "project", registry);
                output.put("recipe", result.lock().recipe());
                output.put("runtime", result.lock().runtime());
                output.put("deployment", result.lock().deployment());
                output.put("generatedFileCount", result.generatedFileCount());
                output.put("acknowledgements", result.lock().acknowledgements());
                out.println(json.writeValueAsString(output));
            } else {
                out.printf(Locale.ROOT,
                        "VALID_PROJECT recipe=%s runtime=%s deployment=%s files=%d "
                                + "acknowledgements=%s%n",
                        result.lock().recipe(), result.lock().runtime(),
                        result.lock().deployment(), result.generatedFileCount(),
                        result.lock().acknowledgements());
            }
            return EXIT_OK;
        }
        ResolvedAppChainConfiguration resolved = resolve(parsed, registry);
        ResolvedValidationResult result = new AppChainResolvedValidator(registry, List.of())
                .validate(resolved.values());
        if (parsed.format() == Format.JSON) {
            Map<String, Object> output = validationEnvelope(
                    result.valid() ? "VALID_RESOLVED" : "INVALID_RESOLVED",
                    "resolved", registry);
            output.put("profile", emptyToNull(resolved.profile()));
            output.put("declaredSources", parsed.configs().stream()
                    .map(AppChainDevtoolsCli::fileName).toList());
            output.put("environmentIncluded", resolved.environmentIncluded());
            output.put("systemPropertiesIncluded", resolved.systemPropertiesIncluded());
            output.put("appChainPropertyCount", result.appChainPropertyCount());
            output.put("recognizedPropertyCount", result.recognizedPropertyCount());
            output.put("chainCount", result.chainCount());
            output.put("errorCount", result.errorCount());
            output.put("warningCount", result.warningCount());
            output.put("infoCount", result.infoCount());
            output.put("diagnostics", result.diagnostics());
            out.println(json.writeValueAsString(output));
        } else {
            writeDiagnostics(result.diagnostics(), out);
            out.printf(Locale.ROOT,
                    "%s mode=resolved sources=%d chains=%d properties=%d recognized=%d "
                            + "errors=%d warnings=%d info=%d profile=%s environment=%s system=%s%n",
                    result.valid() ? "VALID_RESOLVED" : "INVALID_RESOLVED",
                    parsed.configs().size(), result.chainCount(), result.appChainPropertyCount(),
                    result.recognizedPropertyCount(), result.errorCount(), result.warningCount(),
                    result.infoCount(), resolved.profile().isEmpty() ? "none" : resolved.profile(),
                    resolved.environmentIncluded(), resolved.systemPropertiesIncluded());
        }
        return result.valid() ? EXIT_OK : EXIT_INVALID_CONFIG;
    }

    private int validateTemplate(
            ParsedArguments parsed,
            AppChainPropertyRegistry registry,
            PrintWriter out) throws IOException {
        Map<String, Object> values = configLoader.load(parsed.targetPath());
        TemplateContract contract = parsed.templateContract() == null
                ? null : descriptorLoader.loadContract(parsed.templateContract());
        TemplateValidationResult result = new AppChainTemplateValidator(registry)
                .validate(values, contract);
        if (parsed.format() == Format.JSON) {
            Map<String, Object> output = validationEnvelope(
                    result.valid() ? "VALID_TEMPLATE" : "INVALID_TEMPLATE",
                    "template", registry);
            output.put("file", fileName(parsed.targetPath()));
            output.put("templateContract", contract == null ? null : contract.id());
            output.put("appChainPropertyCount", result.appChainPropertyCount());
            output.put("recognizedPropertyCount", result.recognizedPropertyCount());
            output.put("errorCount", result.errorCount());
            output.put("warningCount", result.warningCount());
            output.put("infoCount", result.infoCount());
            output.put("diagnostics", result.diagnostics());
            out.println(json.writeValueAsString(output));
        } else {
            writeDiagnostics(result.diagnostics(), out);
            out.printf(Locale.ROOT,
                    "%s mode=template file=%s properties=%d recognized=%d errors=%d "
                            + "warnings=%d info=%d contract=%s coverage=PARTIAL%n",
                    result.valid() ? "VALID_TEMPLATE" : "INVALID_TEMPLATE",
                    fileName(parsed.targetPath()), result.appChainPropertyCount(),
                    result.recognizedPropertyCount(), result.errorCount(),
                    result.warningCount(), result.infoCount(),
                    contract == null ? "none" : contract.id());
        }
        return result.valid() ? EXIT_OK : EXIT_INVALID_CONFIG;
    }

    private int effective(
            ParsedArguments parsed,
            AppChainPropertyRegistry registry,
            PrintWriter out) throws IOException {
        ResolvedAppChainConfiguration resolved = resolve(parsed, registry);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "EFFECTIVE_CONFIG");
        output.put("mode", "resolved");
        output.put("profile", emptyToNull(resolved.profile()));
        output.put("environmentIncluded", resolved.environmentIncluded());
        output.put("systemPropertiesIncluded", resolved.systemPropertiesIncluded());
        output.put("sources", resolved.sources());
        Map<String, Object> values = new TreeMap<>();
        for (EffectiveConfigValue value : resolved.values().values()) {
            boolean secret = isSecret(value.key(), registry);
            Object displayed = secret ? "<redacted>" : value.value();
            if (parsed.showSources()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("value", displayed);
                detail.put("source", value.source());
                detail.put("sourceKind", value.sourceKind());
                detail.put("sourceOrdinal", value.sourceOrdinal());
                detail.put("explicit", value.explicit());
                registry.find(value.key()).ifPresent(match -> {
                    detail.put("owner", match.definition().owner());
                    detail.put("scope", match.definition().scope());
                    detail.put("changePolicy", match.definition().changePolicy());
                    detail.put("coverage", match.definition().coverage());
                });
                values.put(value.key(), detail);
            } else {
                values.put(value.key(), displayed);
            }
        }
        output.put("values", values);
        ObjectMapper writer = parsed.format() == Format.JSON ? json : yaml;
        out.println(writer.writeValueAsString(output).stripTrailing());
        return EXIT_OK;
    }

    private ResolvedAppChainConfiguration resolve(
            ParsedArguments parsed,
            AppChainPropertyRegistry registry) throws IOException {
        return resolver.resolve(parsed.configs(), parsed.profile(),
                parsed.includeEnvironment(), parsed.includeSystemProperties(), registry);
    }

    private int explain(
            ParsedArguments parsed,
            AppChainPropertyRegistry registry,
            PrintWriter out) throws IOException {
        String normalized = AppChainPropertyRegistry.normalizeKey(parsed.target());
        var property = registry.find(normalized);
        if (property.isPresent()) {
            writeProperty(parsed.format(), property.orElseThrow().definition(), out);
            return EXIT_OK;
        }
        var namespace = registry.dynamicNamespace(normalized);
        if (namespace.isPresent()) {
            writeNamespace(parsed.format(), namespace.orElseThrow(), out);
            return EXIT_OK;
        }
        String suggestion = registry.nearestKey(normalized)
                .map(key -> "; did you mean '" + key + "'?").orElse("");
        out.println("UNKNOWN_PROPERTY " + normalized + suggestion);
        return EXIT_INVALID_CONFIG;
    }

    private void writeProperty(
            Format format,
            AppChainPropertyDefinition property,
            PrintWriter out) throws IOException {
        if (format == Format.JSON) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("kind", "property");
            output.put("property", AppChainMetadataExporter.catalogProperty(property));
            out.println(json.writeValueAsString(output));
            return;
        }
        out.println("PROPERTY\t" + property.key());
        out.println("OWNER\t" + property.owner());
        out.println("TYPE\t" + property.type());
        out.println("DEFAULT\t" + displayDefault(property));
        out.println("BOUNDS\t" + bounds(property));
        out.println("SCOPE\t" + property.scope());
        out.println("CHANGE_POLICY\t" + property.changePolicy());
        out.println("INDEXED\t" + property.indexed());
        out.println("SECRET\t" + property.secret());
        out.println("PROVENANCE\t" + property.constraintProvenance());
        out.println("COVERAGE\t" + property.coverage());
        out.println("DESCRIPTION\t" + property.description());
    }

    private void writeNamespace(
            Format format,
            DynamicNamespaceDefinition namespace,
            PrintWriter out) throws IOException {
        if (format == Format.JSON) {
            out.println(json.writeValueAsString(Map.of(
                    "kind", "dynamicNamespace", "namespace", namespace)));
            return;
        }
        out.println("DYNAMIC_NAMESPACE\t" + namespace.prefix());
        out.println("OWNER\t" + namespace.owner());
        out.println("COVERAGE\t" + namespace.coverage());
        out.println("DESCRIPTION\t" + namespace.description());
    }

    private static Map<String, Object> validationEnvelope(
            String status,
            String mode,
            AppChainPropertyRegistry registry) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", status);
        output.put("mode", mode);
        output.put("metadataSources", registry.sources().stream()
                .map(AppChainMetadataSource::id).toList());
        return output;
    }

    private static void writeDiagnostics(
            List<ValidationDiagnostic> diagnostics,
            PrintWriter out) {
        for (ValidationDiagnostic diagnostic : diagnostics) {
            out.printf(Locale.ROOT, "%s %s%s - %s%n",
                    diagnostic.severity(), diagnostic.code(),
                    diagnostic.key().isBlank() ? "" : " " + diagnostic.key(),
                    diagnostic.message());
        }
    }

    private static boolean isSecret(String key, AppChainPropertyRegistry registry) {
        if (registry.find(key).map(match -> match.definition().secret()).orElse(false)) {
            return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("mnemonic")
                || normalized.endsWith(".signing-key") || normalized.endsWith(".api-key")
                || normalized.endsWith(".private-key");
    }

    private static String displayDefault(AppChainPropertyDefinition property) {
        if (property.secret()) {
            return "<redacted>";
        }
        return property.defaultValue() == null ? "<none>" : property.defaultValue();
    }

    private static String bounds(AppChainPropertyDefinition property) {
        List<String> parts = new ArrayList<>();
        if (property.minimum() != null) parts.add("min=" + property.minimum());
        if (property.maximum() != null) parts.add("max=" + property.maximum());
        if (property.minimumUtf8Bytes() != null) {
            parts.add("minUtf8Bytes=" + property.minimumUtf8Bytes());
        }
        if (property.maximumUtf8Bytes() != null) {
            parts.add("maxUtf8Bytes=" + property.maximumUtf8Bytes());
        }
        if (property.maximumItems() != null) parts.add("maxItems=" + property.maximumItems());
        if (!property.allowedValues().isEmpty()) {
            parts.add("allowed=" + property.allowedValues().stream().sorted().toList());
        }
        return parts.isEmpty() ? "<none>" : String.join(" ", parts);
    }

    private static ParsedArguments parse(String[] args) {
        int cursor = 0;
        if (cursor < args.length && "appchain".equals(args[cursor])) cursor++;
        if (cursor >= args.length || !"config".equals(args[cursor++])) {
            throw usage("Expected 'config'");
        }
        if (cursor >= args.length) throw usage("A config command is required");
        Command command = switch (args[cursor++]) {
            case "validate" -> Command.VALIDATE;
            case "effective" -> Command.EFFECTIVE;
            case "explain" -> Command.EXPLAIN;
            default -> throw usage("Config command must be 'validate', 'effective', or 'explain'");
        };

        Format format = command == Command.EFFECTIVE ? Format.YAML : Format.TEXT;
        Mode mode = null;
        String templateContract = null;
        String profile = "";
        List<Path> metadata = new ArrayList<>();
        List<Path> configs = new ArrayList<>();
        String target = null;
        boolean formatSeen = false;
        boolean includeEnvironment = false;
        boolean includeSystemProperties = false;
        boolean showSources = false;
        while (cursor < args.length) {
            String argument = args[cursor++];
            switch (argument) {
                case "--format" -> {
                    if (formatSeen) throw usage("--format may be specified only once");
                    formatSeen = true;
                    format = switch (value(args, cursor++, argument)) {
                        case "text" -> Format.TEXT;
                        case "json" -> Format.JSON;
                        case "yaml" -> Format.YAML;
                        default -> throw usage("--format must be 'text', 'json', or 'yaml'");
                    };
                }
                case "--mode" -> {
                    if (mode != null || command == Command.EXPLAIN) {
                        throw usage("--mode is accepted once for validate/effective");
                    }
                    mode = switch (value(args, cursor++, argument)) {
                        case "template" -> Mode.TEMPLATE;
                        case "resolved" -> Mode.RESOLVED;
                        case "project" -> Mode.PROJECT;
                        default -> throw usage("--mode must be 'template', 'resolved', or 'project'");
                    };
                }
                case "--config" -> configs.add(path(value(args, cursor++, argument)));
                case "--template-contract" -> {
                    if (templateContract != null) {
                        throw usage("--template-contract may be specified only once");
                    }
                    templateContract = value(args, cursor++, argument);
                }
                case "--metadata" -> metadata.add(path(value(args, cursor++, argument)));
                case "--profile" -> {
                    if (!profile.isEmpty()) throw usage("--profile may be specified only once");
                    profile = value(args, cursor++, argument);
                }
                case "--include-environment" -> includeEnvironment = true;
                case "--include-system-properties" -> includeSystemProperties = true;
                case "--show-sources" -> showSources = true;
                default -> {
                    if (argument.startsWith("--")) {
                        throw usage("Unknown option: " + safeArgument(argument));
                    }
                    if (target != null) throw usage("Only one positional target is accepted");
                    target = argument;
                }
            }
        }

        validateArguments(command, mode, format, target, configs, templateContract,
                profile, includeEnvironment, includeSystemProperties, showSources);
        if (command == Command.EXPLAIN
                && (target.length() > 512 || target.chars().anyMatch(Character::isISOControl))) {
            throw usage("Property must be safe text of at most 512 characters");
        }
        return new ParsedArguments(command, mode, format, target,
                command == Command.VALIDATE
                        && (mode == Mode.TEMPLATE || mode == Mode.PROJECT) ? path(target) : null,
                templateContract, List.copyOf(metadata), List.copyOf(configs), profile,
                includeEnvironment, includeSystemProperties, showSources);
    }

    private static void validateArguments(
            Command command,
            Mode mode,
            Format format,
            String target,
            List<Path> configs,
            String templateContract,
            String profile,
            boolean includeEnvironment,
            boolean includeSystemProperties,
            boolean showSources) {
        if (command == Command.EXPLAIN) {
            if (target == null) throw usage("A property is required");
            if (!configs.isEmpty() || mode != null || templateContract != null
                    || !profile.isEmpty() || includeEnvironment || includeSystemProperties
                    || showSources || format == Format.YAML) {
                throw usage("explain accepts only --format, --metadata, and one property");
            }
            return;
        }
        if (mode == null) throw usage(command.name().toLowerCase(Locale.ROOT) + " requires --mode");
        if (command == Command.EFFECTIVE && mode != Mode.RESOLVED) {
            throw usage("effective supports only '--mode resolved'");
        }
        if (mode == Mode.TEMPLATE || mode == Mode.PROJECT) {
            if (command != Command.VALIDATE || target == null || !configs.isEmpty()) {
                throw usage(mode.name().toLowerCase(Locale.ROOT)
                        + " validate requires one positional path");
            }
            if (!profile.isEmpty() || includeEnvironment || includeSystemProperties
                    || showSources || format == Format.YAML) {
                throw usage(mode.name().toLowerCase(Locale.ROOT)
                        + " validate does not accept resolved-mode options");
            }
            if (mode == Mode.PROJECT && templateContract != null) {
                throw usage("--template-contract is accepted only in template mode");
            }
        } else {
            if (target != null || configs.isEmpty()) {
                throw usage("resolved commands require one or more --config sources");
            }
            if (templateContract != null) {
                throw usage("--template-contract is accepted only in template mode");
            }
            if (command == Command.EFFECTIVE && format == Format.TEXT) {
                throw usage("effective --format must be 'yaml' or 'json'");
            }
            if (command == Command.VALIDATE && (format == Format.YAML || showSources)) {
                throw usage("resolved validate supports text/json and not --show-sources");
            }
        }
    }

    private static boolean helpRequested(String[] args) {
        for (String argument : args) {
            if ("help".equals(argument) || "-h".equals(argument) || "--help".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static boolean projectCommand(String[] args) {
        int cursor = args.length > 0 && "appchain".equals(args[0]) ? 1 : 0;
        if (cursor >= args.length) return false;
        return switch (args[cursor]) {
            case "init", "render", "recipes", "capabilities", "doctor", "diff", "migrate" -> true;
            default -> false;
        };
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) throw usage(option + " requires a value");
        return args[index];
    }

    private static Path path(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException failure) {
            throw usage("Path is invalid");
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "<config>" : safeArgument(name.toString());
    }

    private static String safeArgument(String value) {
        StringBuilder safe = new StringBuilder(Math.min(value.length(), 512));
        value.codePoints().limit(512).forEach(codePoint ->
                safe.appendCodePoint(Character.isISOControl(codePoint) ? '?' : codePoint));
        return safe.toString();
    }

    private static String safeDiagnostic(IOException failure, ParsedArguments parsed) {
        String message = firstLine(failure.getMessage());
        List<Path> paths = new ArrayList<>(parsed.configs());
        if (parsed.targetPath() != null) paths.add(parsed.targetPath());
        paths.addAll(parsed.metadata());
        for (Path path : paths) {
            message = message.replace(path.toAbsolutePath().toString(), fileName(path));
        }
        return safeArgument(bounded(message));
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) return "unknown failure";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String bounded(String message) {
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private static Object emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static UsageException usage(String message) {
        return new UsageException(message);
    }

    private enum Command { VALIDATE, EFFECTIVE, EXPLAIN }

    private enum Mode { TEMPLATE, RESOLVED, PROJECT }

    private enum Format { TEXT, JSON, YAML }

    private record ParsedArguments(
            Command command,
            Mode mode,
            Format format,
            String target,
            Path targetPath,
            String templateContract,
            List<Path> metadata,
            List<Path> configs,
            String profile,
            boolean includeEnvironment,
            boolean includeSystemProperties,
            boolean showSources) {
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
