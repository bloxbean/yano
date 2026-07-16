package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, secret-file-only configuration shared by host and Compose deployments. */
public record DemoConfig(String chainId,
                         List<URI> yanoUrls,
                         Set<String> yanoMemberKeys,
                         int yanoThreshold,
                         SecretValue yanoApiKey,
                         Path sampleFile,
                         Path reportDirectory,
                         String evidenceId,
                         S3Settings s3,
                         IpfsSettings ipfs,
                         KafkaSettings kafka,
                         Duration timeout,
                         Duration pollInterval,
                         boolean requireAnchor) {
    private static final long MAX_CONFIG_BYTES = 65_536;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern CONNECTOR_ALIAS = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern OBJECT_SEGMENT = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._~!$'()+,;=@-]{0,127}");
    private static final Set<String> KEYS = Set.of(
            "demo.chain-id", "demo.yano.urls", "demo.yano.api-key-file",
            "demo.yano.member-keys", "demo.yano.threshold",
            "demo.sample-file", "demo.report-directory", "demo.evidence-id",
            "s3.endpoint", "s3.region", "s3.access-key-file", "s3.secret-key-file",
            "s3.source-bucket", "s3.source-prefix", "s3.destination-bucket",
            "s3.destination-prefix", "s3.target", "s3.target-id",
            "s3.encryption-policy-id", "s3.retention-policy-id", "s3.path-style",
            "ipfs.api-url", "ipfs.target", "ipfs.target-id", "ipfs.replication-policy",
            "kafka.bootstrap-servers", "kafka.target", "kafka.target-id",
            "kafka.topic-alias", "kafka.physical-topic",
            "scenario.timeout-seconds", "scenario.poll-interval-millis",
            "scenario.require-anchor");

    public DemoConfig {
        yanoUrls = List.copyOf(yanoUrls);
        yanoMemberKeys = Set.copyOf(yanoMemberKeys);
    }

    /** Loads a bounded properties file and rejects duplicates, unknown keys, and inline secrets. */
    public static DemoConfig load(Path configFile) {
        Map<String, String> values = readStrict(configFile);
        Path base = configFile.toAbsolutePath().normalize().getParent();
        try {
            String chainId = identifier(required(values, "demo.chain-id"));
            List<URI> yanoUrls = commaSeparatedUris(required(values, "demo.yano.urls"));
            Set<String> yanoMemberKeys = memberKeys(
                    required(values, "demo.yano.member-keys"));
            int yanoThreshold = (int) unsigned(
                    required(values, "demo.yano.threshold"), 1, 3);
            if (yanoMemberKeys.size() != 3 || yanoThreshold != 2) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            String apiKeyFile = values.get("demo.yano.api-key-file");
            SecretValue apiKey = apiKeyFile == null ? null
                    : SecretFiles.read(resolve(base,
                    required(values, "demo.yano.api-key-file")));
            Path sample = resolve(base, required(values, "demo.sample-file"));
            Path reports = resolve(base, required(values, "demo.report-directory"));
            String evidenceId = evidenceId(required(values, "demo.evidence-id"));

            S3Settings s3 = new S3Settings(
                    origin(required(values, "s3.endpoint")),
                    identifier(required(values, "s3.region")),
                    SecretFiles.read(resolve(base, required(values, "s3.access-key-file"))),
                    SecretFiles.read(resolve(base, required(values, "s3.secret-key-file"))),
                    resource(required(values, "s3.source-bucket")),
                    prefix(required(values, "s3.source-prefix")),
                    resource(required(values, "s3.destination-bucket")),
                    prefix(required(values, "s3.destination-prefix")),
                    connectorAlias(required(values, "s3.target")),
                    connectorAlias(required(values, "s3.target-id")),
                    connectorAlias(required(values, "s3.encryption-policy-id")),
                    connectorAlias(required(values, "s3.retention-policy-id")),
                    bool(values.getOrDefault("s3.path-style", "true")));
            IpfsSettings ipfs = new IpfsSettings(
                    origin(required(values, "ipfs.api-url")),
                    connectorAlias(required(values, "ipfs.target")),
                    connectorAlias(required(values, "ipfs.target-id")),
                    optionalConnectorAlias(values.get("ipfs.replication-policy")));
            KafkaSettings kafka = new KafkaSettings(
                    bounded(required(values, "kafka.bootstrap-servers"), 1, 1_024),
                    connectorAlias(required(values, "kafka.target")),
                    connectorAlias(required(values, "kafka.target-id")),
                    connectorAlias(required(values, "kafka.topic-alias")),
                    topic(required(values, "kafka.physical-topic")));

            Duration timeout = Duration.ofSeconds(unsigned(values.getOrDefault(
                    "scenario.timeout-seconds", "300"), 10, 3_600));
            Duration poll = Duration.ofMillis(unsigned(values.getOrDefault(
                    "scenario.poll-interval-millis", "500"), 50, 10_000));
            boolean requireAnchor = bool(values.getOrDefault(
                    "scenario.require-anchor", "true"));
            return new DemoConfig(chainId, yanoUrls, yanoMemberKeys, yanoThreshold,
                    apiKey, sample, reports, evidenceId, s3, ipfs, kafka,
                    timeout, poll, requireAnchor);
        } catch (DemoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static Map<String, String> readStrict(Path path) {
        try {
            if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                    || Files.size(path) > MAX_CONFIG_BYTES) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
                if (line.endsWith("\\")) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (!KEYS.contains(key)) {
                    throw new DemoException(DemoError.UNKNOWN_CONFIG_KEY);
                }
                if (values.putIfAbsent(key, value) != null) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
            }
            return values;
        } catch (DemoException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new DemoException(DemoError.MISSING_CONFIG_KEY);
        }
        return value;
    }

    private static List<URI> commaSeparatedUris(String value) {
        String[] pieces = value.split(",", -1);
        if (pieces.length != 3) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        List<URI> uris = new ArrayList<>();
        Set<URI> distinct = new LinkedHashSet<>();
        for (String piece : pieces) {
            URI uri = yanoEndpoint(piece.trim());
            if (!distinct.add(uri)) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            uris.add(uri);
        }
        return uris;
    }

    private static URI yanoEndpoint(String value) {
        URI uri = httpUri(value);
        String path = uri.getRawPath();
        if (path == null || path.isBlank() || path.equals("/")
                || path.contains("%") || path.contains("//")
                || path.contains("\\") || path.endsWith("/")
                || List.of(path.split("/")).contains("..")
                || List.of(path.split("/")).contains(".")) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        try {
            int port = uri.getPort();
            if (("http".equals(uri.getScheme()) && port == 80)
                    || ("https".equals(uri.getScheme()) && port == 443)) {
                port = -1;
            }
            return new URI(uri.getScheme(), null,
                    uri.getHost().toLowerCase(Locale.ROOT), port,
                    uri.getPath(), null, null);
        } catch (URISyntaxException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static Set<String> memberKeys(String value) {
        String[] pieces = value.split(",", -1);
        Set<String> keys = new LinkedHashSet<>();
        for (String piece : pieces) {
            if (!piece.matches("[0-9a-f]{64}") || !keys.add(piece)) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
        }
        return Set.copyOf(keys);
    }

    private static URI origin(String value) {
        URI uri = httpUri(value);
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (URISyntaxException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static URI httpUri(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            return uri;
        } catch (URISyntaxException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize().toAbsolutePath();
    }

    private static String identifier(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return value;
    }

    private static String connectorAlias(String value) {
        if (!CONNECTOR_ALIAS.matcher(value).matches()) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return value;
    }

    private static String optionalConnectorAlias(String value) {
        return value == null || value.isBlank() ? null : connectorAlias(value);
    }

    private static String evidenceId(String value) {
        if (!value.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return value;
    }

    private static String resource(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return value;
    }

    private static String prefix(String value) {
        if (value.isBlank()) {
            return "";
        }
        if (value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('%') >= 0
                || value.length() > 512) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        String[] segments = value.split("/", -1);
        if (segments.length > 32) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")
                    || !OBJECT_SEGMENT.matcher(segment).matches()) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
        }
        return value;
    }

    private static String topic(String value) {
        if (value.equals(".") || value.equals("..")
                || !value.matches("[A-Za-z0-9._-]{1,249}")) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return value;
    }

    private static String bounded(String value, int minimum, int maximum) {
        if (value.length() < minimum || value.length() > maximum
                || value.chars().anyMatch(character -> character < 0x21 || character == 0x7f)) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return value;
    }

    private static long unsigned(String value, long minimum, long maximum) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        long number = Long.parseLong(value);
        if (number < minimum || number > maximum) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return number;
    }

    private static boolean bool(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new DemoException(DemoError.INVALID_CONFIG);
    }

    public record S3Settings(URI endpoint,
                             String region,
                             SecretValue accessKey,
                             SecretValue secretKey,
                             String sourceBucket,
                             String sourcePrefix,
                             String destinationBucket,
                             String destinationPrefix,
                             String target,
                             String targetId,
                             String encryptionPolicyId,
                             String retentionPolicyId,
                             boolean pathStyle) {
    }

    public record IpfsSettings(URI apiUrl,
                               String target,
                               String targetId,
                               String replicationPolicy) {
    }

    public record KafkaSettings(String bootstrapServers,
                                String target,
                                String targetId,
                                String topicAlias,
                                String physicalTopic) {
    }
}
