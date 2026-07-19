package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainMetadataSource;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyDefinition;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.AppChainTemplateValidator;
import com.bloxbean.cardano.yano.appchain.config.DynamicNamespaceDefinition;
import com.bloxbean.cardano.yano.appchain.config.TemplateContract;
import com.bloxbean.cardano.yano.appchain.config.TemplateValidationResult;
import com.bloxbean.cardano.yano.appchain.config.ValidationDiagnostic;
import com.fasterxml.jackson.databind.ObjectMapper;

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

/** Offline {@code yano appchain config validate/explain} command-line entry point. */
public final class AppChainDevtoolsCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_INVALID_CONFIG = 2;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_IO = 74;

    private static final String USAGE = """
            Usage: yano appchain config validate --mode template [options] <config.yml>
               or: yano appchain config explain [options] <property>
            Options:
              --format text|json
              --metadata <descriptor|plugin.jar>       repeatable
              --template-contract <file|builtin:cluster>  validate only
            """.stripTrailing();

    private final AppChainConfigFileLoader configLoader;
    private final AppChainDescriptorLoader descriptorLoader;
    private final ObjectMapper json;

    public AppChainDevtoolsCli() {
        this(new AppChainConfigFileLoader(), new AppChainDescriptorLoader(), new ObjectMapper());
    }

    AppChainDevtoolsCli(
            AppChainConfigFileLoader configLoader,
            AppChainDescriptorLoader descriptorLoader,
            ObjectMapper json) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.descriptorLoader = Objects.requireNonNull(descriptorLoader, "descriptorLoader");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** Run without closing the supplied streams. */
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

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
            int exit = parsed.command() == Command.VALIDATE
                    ? validate(parsed, registry, out)
                    : explain(parsed, registry, out);
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
        for (Path descriptor : descriptors) {
            sources.add(descriptorLoader.loadMetadata(descriptor).toSource());
        }
        return AppChainPropertyRegistry.withSources(sources);
    }

    private int validate(
            ParsedArguments parsed,
            AppChainPropertyRegistry registry,
            PrintWriter out) throws IOException {
        Map<String, Object> values = configLoader.load(parsed.targetPath());
        TemplateContract contract = parsed.templateContract() == null
                ? null : descriptorLoader.loadContract(parsed.templateContract());
        TemplateValidationResult result = new AppChainTemplateValidator(registry)
                .validate(values, contract);
        if (parsed.format() == Format.JSON) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", result.valid() ? "VALID_TEMPLATE" : "INVALID_TEMPLATE");
            output.put("mode", "template");
            output.put("file", fileName(parsed.targetPath()));
            output.put("templateContract", contract == null ? null : contract.id());
            output.put("metadataSources", registry.sources().stream()
                    .map(AppChainMetadataSource::id).toList());
            output.put("appChainPropertyCount", result.appChainPropertyCount());
            output.put("recognizedPropertyCount", result.recognizedPropertyCount());
            output.put("errorCount", result.errorCount());
            output.put("warningCount", result.warningCount());
            output.put("infoCount", result.infoCount());
            output.put("diagnostics", result.diagnostics());
            out.println(json.writeValueAsString(output));
        } else {
            for (ValidationDiagnostic diagnostic : result.diagnostics()) {
                out.printf(Locale.ROOT, "%s %s%s - %s%n",
                        diagnostic.severity(), diagnostic.code(),
                        diagnostic.key().isBlank() ? "" : " " + diagnostic.key(),
                        diagnostic.message());
            }
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
                    "kind", "dynamicNamespace",
                    "namespace", namespace)));
            return;
        }
        out.println("DYNAMIC_NAMESPACE\t" + namespace.prefix());
        out.println("OWNER\t" + namespace.owner());
        out.println("COVERAGE\t" + namespace.coverage());
        out.println("DESCRIPTION\t" + namespace.description());
    }

    private static String displayDefault(AppChainPropertyDefinition property) {
        if (property.secret()) {
            return "<redacted>";
        }
        return property.defaultValue() == null ? "<none>" : property.defaultValue();
    }

    private static String bounds(AppChainPropertyDefinition property) {
        List<String> parts = new ArrayList<>();
        if (property.minimum() != null) {
            parts.add("min=" + property.minimum());
        }
        if (property.maximum() != null) {
            parts.add("max=" + property.maximum());
        }
        if (property.minimumUtf8Bytes() != null) {
            parts.add("minUtf8Bytes=" + property.minimumUtf8Bytes());
        }
        if (property.maximumUtf8Bytes() != null) {
            parts.add("maxUtf8Bytes=" + property.maximumUtf8Bytes());
        }
        if (property.maximumItems() != null) {
            parts.add("maxItems=" + property.maximumItems());
        }
        if (!property.allowedValues().isEmpty()) {
            parts.add("allowed=" + property.allowedValues().stream().sorted().toList());
        }
        return parts.isEmpty() ? "<none>" : String.join(" ", parts);
    }

    private static ParsedArguments parse(String[] args) {
        int cursor = 0;
        if (cursor < args.length && "appchain".equals(args[cursor])) {
            cursor++;
        }
        if (cursor >= args.length || !"config".equals(args[cursor++])) {
            throw usage("Expected 'config'");
        }
        if (cursor >= args.length) {
            throw usage("A config command is required");
        }
        Command command = switch (args[cursor++]) {
            case "validate" -> Command.VALIDATE;
            case "explain" -> Command.EXPLAIN;
            default -> throw usage("Config command must be 'validate' or 'explain'");
        };

        Format format = Format.TEXT;
        boolean formatSeen = false;
        boolean modeSeen = false;
        String templateContract = null;
        List<Path> metadata = new ArrayList<>();
        String target = null;
        while (cursor < args.length) {
            String argument = args[cursor++];
            switch (argument) {
                case "--format" -> {
                    if (formatSeen) {
                        throw usage("--format may be specified only once");
                    }
                    formatSeen = true;
                    String value = value(args, cursor++, argument);
                    format = switch (value) {
                        case "text" -> Format.TEXT;
                        case "json" -> Format.JSON;
                        default -> throw usage("--format must be 'text' or 'json'");
                    };
                }
                case "--mode" -> {
                    if (command != Command.VALIDATE || modeSeen) {
                        throw usage("--mode is required once and only for validate");
                    }
                    modeSeen = true;
                    if (!"template".equals(value(args, cursor++, argument))) {
                        throw usage("M0a supports only '--mode template'");
                    }
                }
                case "--template-contract" -> {
                    if (command != Command.VALIDATE || templateContract != null) {
                        throw usage("--template-contract is accepted once and only for validate");
                    }
                    templateContract = value(args, cursor++, argument);
                }
                case "--metadata" -> metadata.add(path(value(args, cursor++, argument)));
                default -> {
                    if (argument.startsWith("--")) {
                        throw usage("Unknown option: " + safeArgument(argument));
                    }
                    if (target != null) {
                        throw usage("Exactly one config file or property is required");
                    }
                    target = argument;
                }
            }
        }
        if (target == null) {
            throw usage(command == Command.VALIDATE
                    ? "A configuration file is required" : "A property is required");
        }
        if (command == Command.EXPLAIN
                && (target.length() > 512
                || target.chars().anyMatch(Character::isISOControl))) {
            throw usage("Property must be safe text of at most 512 characters");
        }
        if (command == Command.VALIDATE && !modeSeen) {
            throw usage("validate requires '--mode template'");
        }
        return new ParsedArguments(command, format, target,
                command == Command.VALIDATE ? path(target) : null,
                templateContract, List.copyOf(metadata));
    }

    private static boolean helpRequested(String[] args) {
        for (String argument : args) {
            if ("help".equals(argument) || "-h".equals(argument)
                    || "--help".equals(argument)) {
                return true;
            }
        }
        return false;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw usage(option + " requires a value");
        }
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
        if (parsed.targetPath() != null) {
            message = message.replace(parsed.targetPath().toAbsolutePath().toString(),
                    fileName(parsed.targetPath()));
        }
        for (Path descriptor : parsed.metadata()) {
            message = message.replace(descriptor.toAbsolutePath().toString(),
                    fileName(descriptor));
        }
        return safeArgument(bounded(message));
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "unknown failure";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private static String bounded(String message) {
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private static UsageException usage(String message) {
        return new UsageException(message);
    }

    private enum Command { VALIDATE, EXPLAIN }

    private enum Format { TEXT, JSON }

    private record ParsedArguments(
            Command command,
            Format format,
            String target,
            Path targetPath,
            String templateContract,
            List<Path> metadata) {
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
