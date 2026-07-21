package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginBundleInfo;
import com.bloxbean.cardano.yano.api.plugin.PluginContributionInfo;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Resource-only {@code yano-plugins validate/inspect} command-line entry point. */
public final class PluginCatalogCli {
    /** Command completed successfully. */
    public static final int EXIT_OK = 0;
    /** Artifact metadata or the selected catalog is invalid. */
    public static final int EXIT_INVALID_CATALOG = 2;
    /** Command-line syntax or policy is invalid. */
    public static final int EXIT_USAGE = 64;
    /** Artifact evidence could not be read safely. */
    public static final int EXIT_IO = 74;

    private static final int MAX_DIAGNOSTIC_LENGTH = 512;
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_.-])/(?:[^\\s\\p{Cntrl}\\\"'<>|]+/)*"
                    + "([^/\\s\\p{Cntrl}\\\"'<>|]+)");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_.-])[a-z]:\\\\(?:[^\\s\\p{Cntrl}\\\"'<>|]+\\\\)*"
                    + "([^\\\\/\\s\\p{Cntrl}\\\"'<>|]+)");
    private static final String USAGE = "Usage: yano-plugins validate [policy options] <artifact>..."
            + System.lineSeparator()
            + "   or: yano-plugins inspect [--format table|json] [policy options] <artifact>..."
            + System.lineSeparator()
            + "Policy options: [--api-major <positive-int>] [--api-level <positive-int>]"
            + " [--allow <bundle-id>]..."
            + " [--deny <bundle-id>]... [--]";

    private final PluginCatalogInspector inspector;

    /** Creates a command backed by the strict offline inspector. */
    public PluginCatalogCli() {
        this(new PluginCatalogInspector());
    }

    PluginCatalogCli(PluginCatalogInspector inspector) {
        this.inspector = Objects.requireNonNull(inspector, "inspector");
    }

    /**
     * Runs one command without closing the supplied writers.
     *
     * @param args command arguments, optionally beginning with {@code plugins}
     * @param out normal output
     * @param err diagnostics
     * @return one of the stable {@code EXIT_*} codes
     */
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        Arguments parsed;
        try {
            parsed = parse(args);
        } catch (UsageException failure) {
            err.println(failure.getMessage());
            err.println(USAGE);
            err.flush();
            return EXIT_USAGE;
        }

        try {
            PluginCatalogInspection inspection = inspector.inspect(
                    parsed.artifacts(), parsed.policy());
            if (parsed.command() == Command.VALIDATE) {
                writeValidation(out, inspection);
            } else if (parsed.format() == Format.JSON) {
                writeJson(out, inspection);
            } else {
                writeTable(out, inspection);
            }
            out.flush();
            return EXIT_OK;
        } catch (PluginCatalogException failure) {
            err.println("Plugin catalog is invalid: "
                    + safeDiagnostic(failure, parsed.artifacts()));
            err.flush();
            return EXIT_INVALID_CATALOG;
        } catch (IOException failure) {
            err.println("Plugin catalog could not be read: "
                    + safeDiagnostic(failure, parsed.artifacts()));
            err.flush();
            return EXIT_IO;
        }
    }

    /** Runs the command as a standalone JVM process. */
    public static void main(String[] args) {
        int exit = new PluginCatalogCli().run(
                args, new PrintWriter(System.out), new PrintWriter(System.err));
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    private static Arguments parse(String[] args) {
        int cursor = 0;
        if (cursor < args.length && "plugins".equals(args[cursor])) {
            cursor++;
        }
        if (cursor >= args.length) {
            throw usage("A command is required");
        }
        Command command = switch (args[cursor++]) {
            case "validate" -> Command.VALIDATE;
            case "inspect" -> Command.INSPECT;
            default -> throw usage("Command must be 'validate' or 'inspect'");
        };

        int apiMajor = PluginCatalogInspectionPolicy.current().pluginApiMajor();
        int apiLevel = PluginCatalogInspectionPolicy.current().pluginApiLevel();
        boolean apiMajorSeen = false;
        boolean apiLevelSeen = false;
        Format format = Format.TABLE;
        boolean formatSeen = false;
        Set<String> allow = new TreeSet<>();
        Set<String> deny = new TreeSet<>();
        List<Path> artifacts = new ArrayList<>();
        while (cursor < args.length) {
            String argument = args[cursor++];
            if ("--".equals(argument)) {
                while (cursor < args.length) {
                    artifacts.add(path(args[cursor++]));
                }
                break;
            }
            if (!argument.startsWith("--")) {
                artifacts.add(path(argument));
                while (cursor < args.length) {
                    String trailing = args[cursor++];
                    if (trailing.startsWith("--")) {
                        throw usage("Options must precede artifact paths; use '--' when an "
                                + "artifact path begins with '--'");
                    }
                    artifacts.add(path(trailing));
                }
                break;
            }
            switch (argument) {
                case "--api-major" -> {
                    if (apiMajorSeen) {
                        throw usage("--api-major may be specified only once");
                    }
                    apiMajorSeen = true;
                    String value = value(args, cursor++, argument);
                    try {
                        apiMajor = Integer.parseInt(value);
                    } catch (NumberFormatException failure) {
                        throw usage("--api-major must be a positive integer");
                    }
                    if (apiMajor <= 0) {
                        throw usage("--api-major must be a positive integer");
                    }
                }
                case "--api-level" -> {
                    if (apiLevelSeen) {
                        throw usage("--api-level may be specified only once");
                    }
                    apiLevelSeen = true;
                    String value = value(args, cursor++, argument);
                    try {
                        apiLevel = Integer.parseInt(value);
                    } catch (NumberFormatException failure) {
                        throw usage("--api-level must be a positive integer");
                    }
                    if (apiLevel <= 0) {
                        throw usage("--api-level must be a positive integer");
                    }
                }
                case "--allow" -> allow.add(value(args, cursor++, argument));
                case "--deny" -> deny.add(value(args, cursor++, argument));
                case "--format" -> {
                    if (command != Command.INSPECT) {
                        throw usage("--format is supported only by 'inspect'");
                    }
                    if (formatSeen) {
                        throw usage("--format may be specified only once");
                    }
                    formatSeen = true;
                    String value = value(args, cursor++, argument);
                    format = switch (value) {
                        case "table" -> Format.TABLE;
                        case "json" -> Format.JSON;
                        default -> throw usage("--format must be 'table' or 'json'");
                    };
                }
                default -> throw usage("Unknown option");
            }
        }
        if (artifacts.isEmpty()) {
            throw usage("At least one artifact is required");
        }
        try {
            return new Arguments(command, format,
                    new PluginCatalogInspectionPolicy(apiMajor, apiLevel, allow, deny),
                    List.copyOf(artifacts));
        } catch (IllegalArgumentException failure) {
            throw usage(bounded(failure.getMessage()));
        }
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
            throw usage("Artifact path is invalid");
        }
    }

    private static void writeValidation(
            PrintWriter out,
            PluginCatalogInspection inspection
    ) {
        out.printf(Locale.ROOT,
                "VALID apiMajor=%d apiLevel=%d bundles=%d selected=%d fingerprint=%s%n",
                inspection.pluginApiMajor(), inspection.pluginApiLevel(),
                inspection.bundles().size(),
                inspection.selectedBundleOrder().size(), inspection.fingerprint());
    }

    private static void writeTable(PrintWriter out, PluginCatalogInspection inspection) {
        out.println("PLUGIN_API_MAJOR\t" + inspection.pluginApiMajor());
        out.println("PLUGIN_API_LEVEL\t" + inspection.pluginApiLevel());
        out.println("FINGERPRINT\t" + inspection.fingerprint());
        out.println("SELECTED_ORDER\t" + String.join(",", inspection.selectedBundleOrder()));
        out.println("ID\tVERSION\tSTATUS\tDIGEST_MODE\tDEPENDENCIES\tCONTRIBUTIONS");
        for (PluginBundleInfo bundle : inspection.bundles()) {
            String contributions = bundle.contributions().stream()
                    .map(value -> value.kind() + "/" + value.name())
                    .collect(Collectors.joining(","));
            out.println(String.join("\t",
                    bundle.id(), bundle.version(), bundle.selectionStatus().name(),
                    bundle.digestMode().name(), String.join(",", bundle.dependencies()),
                    contributions));
        }
    }

    private static void writeJson(PrintWriter out, PluginCatalogInspection inspection) {
        StringBuilder json = new StringBuilder(512);
        json.append('{')
                .append("\"pluginApiMajor\":").append(inspection.pluginApiMajor()).append(',')
                .append("\"pluginApiLevel\":").append(inspection.pluginApiLevel()).append(',')
                .append("\"fingerprint\":");
        string(json, inspection.fingerprint());
        json.append(",\"selectedBundleOrder\":");
        strings(json, inspection.selectedBundleOrder());
        json.append(",\"bundles\":[");
        for (int bundleIndex = 0; bundleIndex < inspection.bundles().size(); bundleIndex++) {
            if (bundleIndex > 0) {
                json.append(',');
            }
            PluginBundleInfo bundle = inspection.bundles().get(bundleIndex);
            json.append('{').append("\"id\":");
            string(json, bundle.id());
            json.append(",\"version\":");
            string(json, bundle.version());
            json.append(",\"selected\":").append(bundle.selected())
                    .append(",\"selectionStatus\":");
            string(json, bundle.selectionStatus().name());
            json.append(",\"legacy\":").append(bundle.legacy())
                    .append(",\"source\":");
            string(json, bundle.source().name());
            json.append(",\"digest\":");
            string(json, bundle.digest());
            json.append(",\"digestMode\":");
            string(json, bundle.digestMode().name());
            json.append(",\"dependencies\":");
            strings(json, bundle.dependencies());
            json.append(",\"contributions\":[");
            for (int contributionIndex = 0;
                 contributionIndex < bundle.contributions().size();
                 contributionIndex++) {
                if (contributionIndex > 0) {
                    json.append(',');
                }
                PluginContributionInfo contribution =
                        bundle.contributions().get(contributionIndex);
                json.append('{').append("\"kind\":");
                string(json, contribution.kind());
                json.append(",\"name\":");
                string(json, contribution.name());
                json.append(",\"providerClass\":");
                string(json, contribution.providerClass());
                json.append(",\"trustTier\":");
                string(json, contribution.trustTier().name());
                json.append('}');
            }
            json.append("]}");
        }
        json.append("]}");
        out.println(json);
    }

    private static void strings(StringBuilder json, List<String> values) {
        json.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            string(json, values.get(index));
        }
        json.append(']');
    }

    private static void string(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u%04x".formatted((int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static String safeDiagnostic(Throwable failure, List<Path> artifacts) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = "operation failed";
        }
        for (Path artifact : artifacts) {
            Path fileName = artifact.getFileName();
            String safeName = fileName == null ? "artifact" : fileName.toString();
            if (!artifact.toString().isEmpty()) {
                message = message.replace(artifact.toString(), safeName);
            }
            message = message.replace(artifact.toAbsolutePath().normalize().toString(), safeName);
        }
        message = stripAbsolutePaths(message, UNIX_ABSOLUTE_PATH);
        message = stripAbsolutePaths(message, WINDOWS_ABSOLUTE_PATH);
        StringBuilder printable = new StringBuilder(Math.min(
                message.length(), MAX_DIAGNOSTIC_LENGTH));
        for (int index = 0;
             index < message.length() && printable.length() < MAX_DIAGNOSTIC_LENGTH;
             index++) {
            char character = message.charAt(index);
            printable.append(character >= 0x20 && character <= 0x7e ? character : ' ');
        }
        return printable.toString().trim();
    }

    private static String stripAbsolutePaths(String message, Pattern pattern) {
        Matcher matcher = pattern.matcher(message);
        StringBuilder safe = new StringBuilder(message.length());
        while (matcher.find()) {
            matcher.appendReplacement(safe, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(safe);
        return safe.toString();
    }

    private static String bounded(String value) {
        if (value == null) {
            return "invalid value";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), 128));
        for (int index = 0; index < value.length() && result.length() < 128; index++) {
            char character = value.charAt(index);
            result.append(character >= 0x20 && character <= 0x7e ? character : ' ');
        }
        return result.toString().trim();
    }

    private static UsageException usage(String message) {
        return new UsageException(message);
    }

    private enum Command {
        VALIDATE,
        INSPECT
    }

    private enum Format {
        TABLE,
        JSON
    }

    private record Arguments(
            Command command,
            Format format,
            PluginCatalogInspectionPolicy policy,
            List<Path> artifacts
    ) {
    }

    private static final class UsageException extends RuntimeException {
        private UsageException(String message) {
            super(message);
        }
    }
}
