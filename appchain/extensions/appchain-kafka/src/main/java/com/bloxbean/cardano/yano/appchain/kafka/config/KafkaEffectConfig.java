package com.bloxbean.cardano.yano.appchain.kafka.config;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.kafka.KafkaDestinationFingerprint;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Strict parser for the stripped {@code effects.executors.kafka.*} settings.
 * Unknown keys, ambiguous values, unsafe client overrides, and incomplete
 * security profiles are rejected at startup.
 */
public final class KafkaEffectConfig {
    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern PHYSICAL_TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern USERNAME = Pattern.compile("[!-~]{1,256}");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of("enabled", "detail-archive-path");
    private static final Set<String> TOPIC_KEYS = Set.of("target", "name");
    private static final Set<String> TARGET_KEYS = Set.of(
            "target-id", "bootstrap-servers", "security-profile", "acks",
            "max-block-ms", "request-timeout-ms", "delivery-timeout-ms", "close-timeout-ms",
            "tls.truststore-path", "tls.truststore-password", "tls.truststore-type",
            "tls.keystore-path", "tls.keystore-password", "tls.keystore-type", "tls.key-password",
            "sasl.mechanism", "sasl.username", "sasl.password");
    private static final Set<String> TRUST_KEYS = Set.of(
            "tls.truststore-path", "tls.truststore-password", "tls.truststore-type");
    private static final Set<String> KEYSTORE_KEYS = Set.of(
            "tls.keystore-path", "tls.keystore-password", "tls.keystore-type", "tls.key-password");
    private static final Set<String> SASL_KEYS = Set.of(
            "sasl.mechanism", "sasl.username", "sasl.password");
    private static final int MAX_SETTINGS = 256;
    private static final int MAX_TARGETS = 16;
    private static final int MAX_TOPICS = 64;
    private static final int MAX_BOOTSTRAP_CHARACTERS = 2_048;
    private static final int MAX_SECRET_CHARACTERS = 4_096;
    private static final int MAX_PATH_CHARACTERS = 1_024;
    private static final int DEFAULT_MAX_BLOCK_MS = 5_000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_DELIVERY_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_CLOSE_TIMEOUT_MS = 5_000;

    private final boolean enabled;
    private final Path detailArchivePath;
    private final Map<String, Target> targets;
    private final Map<String, Topic> topics;

    private KafkaEffectConfig(boolean enabled,
                              Path detailArchivePath,
                              Map<String, Target> targets,
                              Map<String, Topic> topics) {
        this.enabled = enabled;
        this.detailArchivePath = detailArchivePath;
        this.targets = Map.copyOf(targets);
        this.topics = Map.copyOf(topics);
    }

    /**
     * Parses a factory's stripped settings map.
     *
     * @param settings settings below {@code effects.executors.kafka.}
     * @return the immutable validated configuration
     */
    public static KafkaEffectConfig parse(Map<String, String> settings) {
        if (settings == null || settings.size() > MAX_SETTINGS) {
            throw invalid("settings must be present and bounded");
        }
        Map<String, String> topLevel = new TreeMap<>();
        Map<String, Map<String, String>> targetValues = new TreeMap<>();
        Map<String, Map<String, String>> topicValues = new TreeMap<>();
        Map<String, String> sortedSettings = new TreeMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = requireKey(entry.getKey());
            sortedSettings.put(key, requireRawValue(entry.getValue()));
        }
        for (Map.Entry<String, String> entry : sortedSettings.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (TOP_LEVEL_KEYS.contains(key)) {
                topLevel.put(key, value);
            } else if (key.startsWith("targets.")) {
                collectNested(key, value, "targets.", TARGET_KEYS, targetValues);
            } else if (key.startsWith("topics.")) {
                collectNested(key, value, "topics.", TOPIC_KEYS, topicValues);
            } else {
                throw invalid("unknown Kafka executor setting");
            }
        }
        if (targetValues.size() > MAX_TARGETS || topicValues.size() > MAX_TOPICS) {
            throw invalid("too many Kafka targets or topics");
        }

        boolean enabled = topLevel.containsKey("enabled")
                ? parseBoolean(topLevel.get("enabled"), "enabled")
                : !targetValues.isEmpty() || !topicValues.isEmpty();
        Path detailArchivePath = topLevel.containsKey("detail-archive-path")
                ? absolutePath(topLevel.get("detail-archive-path"), "detail-archive-path") : null;
        Map<String, Target> targets = parseTargets(targetValues);
        Map<String, Topic> topics = parseTopics(topicValues, targets);
        if (enabled && (targets.isEmpty() || topics.isEmpty())) {
            throw invalid("enabled Kafka executor requires at least one target and topic");
        }
        return new KafkaEffectConfig(enabled, detailArchivePath, targets, topics);
    }

    /**
     * Returns whether this configured contribution should be created.
     *
     * @return {@code true} when effect execution is configured and enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the optional caller-owned durable detail archive path.
     *
     * @return the configured absolute normalized path
     */
    public Optional<Path> detailArchivePath() {
        return Optional.ofNullable(detailArchivePath);
    }

    /**
     * Returns all configured targets by payload alias.
     *
     * @return an immutable map whose values redact sensitive fields from text
     */
    public Map<String, Target> targets() {
        return targets;
    }

    /**
     * Returns one configured target without accepting an endpoint from the payload.
     *
     * @param alias the payload target alias
     * @return the configured target, or empty when unknown
     */
    public Optional<Target> target(String alias) {
        return alias == null ? Optional.empty() : Optional.ofNullable(targets.get(alias));
    }

    /**
     * Returns one configured topic without accepting a physical name from the payload.
     *
     * @param alias the payload topic alias
     * @return the configured topic, or empty when unknown
     */
    public Optional<Topic> topic(String alias) {
        return alias == null ? Optional.empty() : Optional.ofNullable(topics.get(alias));
    }

    /**
     * Resolves a payload target/topic pair only when the topic is bound to that target.
     *
     * @param targetAlias payload target alias
     * @param topicAlias payload topic alias
     * @return the resolved allowlisted destination, or empty for any mismatch
     */
    public Optional<ResolvedTopic> resolve(String targetAlias, String topicAlias) {
        Target target = targets.get(targetAlias);
        Topic topic = topics.get(topicAlias);
        if (target == null || topic == null || !topic.targetAlias.equals(targetAlias)) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedTopic(target, topic));
    }

    /**
     * Returns bounded diagnostics that deliberately omit endpoints, physical
     * topics, paths, credentials, and Kafka client properties.
     *
     * @return a safe immutable diagnostic snapshot
     */
    public Map<String, Object> safeDiagnostics() {
        Map<String, String> profiles = new TreeMap<>();
        targets.forEach((alias, target) -> profiles.put(alias, target.securityProfile.configValue));
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("enabled", enabled);
        diagnostics.put("targetAliases", List.copyOf(new TreeMap<>(targets).keySet()));
        diagnostics.put("topicAliases", List.copyOf(new TreeMap<>(topics).keySet()));
        diagnostics.put("securityProfiles", Map.copyOf(profiles));
        diagnostics.put("detailArchiveConfigured", detailArchivePath != null);
        return Map.copyOf(diagnostics);
    }

    @Override
    public String toString() {
        return "KafkaEffectConfig" + safeDiagnostics();
    }

    private static Map<String, Target> parseTargets(Map<String, Map<String, String>> values) {
        Map<String, Target> result = new LinkedHashMap<>();
        Set<String> targetIds = new java.util.HashSet<>();
        for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
            String alias = entry.getKey();
            Map<String, String> fields = entry.getValue();
            String targetId = alias(fields, "target-id");
            if (!targetIds.add(targetId)) {
                throw invalid("duplicate Kafka target-id");
            }
            SecurityProfile profile = SecurityProfile.parse(required(fields, "security-profile"));
            String bootstrapServers = bootstrapServers(required(fields, "bootstrap-servers"), profile);
            if (!"all".equals(fields.getOrDefault("acks", "all"))) {
                throw invalid("Kafka producer acks must be all");
            }
            int maxBlockMs = integer(fields, "max-block-ms", DEFAULT_MAX_BLOCK_MS, 100, 30_000);
            int requestTimeoutMs = integer(fields, "request-timeout-ms",
                    DEFAULT_REQUEST_TIMEOUT_MS, 1_000, 30_000);
            int deliveryTimeoutMs = integer(fields, "delivery-timeout-ms",
                    DEFAULT_DELIVERY_TIMEOUT_MS, requestTimeoutMs, 60_000);
            int closeTimeoutMs = integer(fields, "close-timeout-ms",
                    DEFAULT_CLOSE_TIMEOUT_MS, 100, 30_000);
            Properties producerProperties = baseProducerProperties(
                    targetId, bootstrapServers, maxBlockMs, requestTimeoutMs, deliveryTimeoutMs);
            applySecurity(profile, fields, producerProperties);
            result.put(alias, new Target(alias, targetId, profile, producerProperties,
                    Duration.ofMillis(deliveryTimeoutMs), Duration.ofMillis(closeTimeoutMs)));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Topic> parseTopics(Map<String, Map<String, String>> values,
                                                   Map<String, Target> targets) {
        Map<String, Topic> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
            String topicAlias = entry.getKey();
            Map<String, String> fields = entry.getValue();
            String targetAlias = alias(fields, "target");
            Target target = targets.get(targetAlias);
            if (target == null) {
                throw invalid("Kafka topic references an unknown target");
            }
            String physicalName = exactText(required(fields, "name"), "topic name", 249);
            if (!PHYSICAL_TOPIC.matcher(physicalName).matches()
                    || physicalName.equals(".") || physicalName.equals("..")) {
                throw invalid("invalid Kafka physical topic");
            }
            ConnectorTargetFingerprint fingerprint = KafkaDestinationFingerprint.compute(
                    target.targetId, physicalName);
            result.put(topicAlias, new Topic(topicAlias, targetAlias, physicalName, fingerprint));
        }
        return Map.copyOf(result);
    }

    private static Properties baseProducerProperties(String targetId,
                                                       String bootstrapServers,
                                                       int maxBlockMs,
                                                       int requestTimeoutMs,
                                                       int deliveryTimeoutMs) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "yano-kafka-effect-" + targetId);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        properties.put(ProducerConfig.ENABLE_METRICS_PUSH_CONFIG, "false");
        properties.put(ProducerConfig.METRIC_REPORTER_CLASSES_CONFIG, List.of());
        properties.put(ProducerConfig.RETRIES_CONFIG, Integer.toString(Integer.MAX_VALUE));
        properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
        properties.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Integer.toString(maxBlockMs));
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, Integer.toString(requestTimeoutMs));
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, Integer.toString(deliveryTimeoutMs));
        properties.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "100");
        properties.put(ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG, "1000");
        properties.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "none");
        properties.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, Integer.toString(32 * 1024));
        properties.put(ProducerConfig.BUFFER_MEMORY_CONFIG, Integer.toString(4 * 1024 * 1024));
        return properties;
    }

    private static void applySecurity(SecurityProfile profile,
                                      Map<String, String> fields,
                                      Properties properties) {
        Set<String> suppliedSecurityKeys = new java.util.HashSet<>(fields.keySet());
        suppliedSecurityKeys.retainAll(union(TRUST_KEYS, KEYSTORE_KEYS, SASL_KEYS));
        switch (profile) {
            case LOCAL_DEMO -> {
                if (!suppliedSecurityKeys.isEmpty()) {
                    throw invalid("local-demo profile does not accept TLS or SASL settings");
                }
                properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            }
            case TLS -> {
                rejectAny(fields, union(KEYSTORE_KEYS, SASL_KEYS), "tls profile");
                properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
                applyTrust(fields, properties);
            }
            case MTLS -> {
                rejectAny(fields, SASL_KEYS, "mtls profile");
                properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
                applyTrust(fields, properties);
                applyKeyStore(fields, properties);
            }
            case SASL_TLS -> {
                rejectAny(fields, KEYSTORE_KEYS, "sasl-tls profile");
                properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
                applyTrust(fields, properties);
                applySasl(fields, properties);
            }
        }
        if (profile != SecurityProfile.LOCAL_DEMO) {
            properties.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
        }
    }

    private static void applyTrust(Map<String, String> fields, Properties properties) {
        boolean hasPath = fields.containsKey("tls.truststore-path");
        boolean hasPassword = fields.containsKey("tls.truststore-password");
        boolean hasType = fields.containsKey("tls.truststore-type");
        if (hasPath != hasPassword || (hasType && !hasPath)) {
            throw invalid("custom truststore requires path and password together");
        }
        if (hasPath) {
            properties.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG,
                    absolutePath(fields.get("tls.truststore-path"), "tls.truststore-path").toString());
            properties.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG,
                    secret(fields.get("tls.truststore-password"), "tls.truststore-password"));
            properties.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG,
                    storeType(fields.getOrDefault("tls.truststore-type", "PKCS12")));
        }
    }

    private static void applyKeyStore(Map<String, String> fields, Properties properties) {
        String path = required(fields, "tls.keystore-path");
        properties.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG,
                absolutePath(path, "tls.keystore-path").toString());
        properties.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG,
                secret(required(fields, "tls.keystore-password"), "tls.keystore-password"));
        properties.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG,
                secret(required(fields, "tls.key-password"), "tls.key-password"));
        properties.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG,
                storeType(fields.getOrDefault("tls.keystore-type", "PKCS12")));
    }

    private static void applySasl(Map<String, String> fields, Properties properties) {
        String mechanism = required(fields, "sasl.mechanism").toUpperCase(Locale.ROOT);
        String loginModule = switch (mechanism) {
            case "PLAIN" -> "org.apache.kafka.common.security.plain.PlainLoginModule";
            case "SCRAM-SHA-256", "SCRAM-SHA-512" ->
                    "org.apache.kafka.common.security.scram.ScramLoginModule";
            default -> throw invalid("unsupported Kafka SASL mechanism");
        };
        String username = exactText(required(fields, "sasl.username"), "sasl.username", 256);
        if (!USERNAME.matcher(username).matches()) {
            throw invalid("invalid Kafka SASL username");
        }
        String password = secret(required(fields, "sasl.password"), "sasl.password");
        properties.put(SaslConfigs.SASL_MECHANISM, mechanism);
        properties.put(SaslConfigs.SASL_JAAS_CONFIG, loginModule + " required username=\""
                + jaasEscape(username) + "\" password=\"" + jaasEscape(password) + "\";");
    }

    private static String bootstrapServers(String value, SecurityProfile profile) {
        String exact = exactText(value, "bootstrap-servers", MAX_BOOTSTRAP_CHARACTERS);
        String[] entries = exact.split(",", -1);
        if (entries.length == 0 || entries.length > 16) {
            throw invalid("Kafka bootstrap server list must contain 1 to 16 entries");
        }
        List<String> normalized = new java.util.ArrayList<>(entries.length);
        for (String entry : entries) {
            if (!entry.equals(entry.trim()) || entry.isEmpty()) {
                throw invalid("invalid Kafka bootstrap server");
            }
            HostPort hostPort = hostPort(entry);
            if (profile == SecurityProfile.LOCAL_DEMO && !isLocalHost(hostPort.host)) {
                throw invalid("local-demo Kafka profile requires a local/private bootstrap host");
            }
            normalized.add(hostPort.rendered);
        }
        return String.join(",", normalized);
    }

    private static HostPort hostPort(String entry) {
        String host;
        String portText;
        if (entry.startsWith("[")) {
            int close = entry.indexOf(']');
            if (close < 2 || close + 2 >= entry.length() || entry.charAt(close + 1) != ':') {
                throw invalid("invalid bracketed Kafka bootstrap server");
            }
            host = entry.substring(1, close).toLowerCase(Locale.ROOT);
            portText = entry.substring(close + 2);
            if (!validIpv6Literal(host)) {
                throw invalid("invalid Kafka IPv6 bootstrap host");
            }
        } else {
            int colon = entry.lastIndexOf(':');
            if (colon < 1 || colon == entry.length() - 1 || entry.indexOf(':') != colon) {
                throw invalid("Kafka bootstrap server must be host:port");
            }
            host = entry.substring(0, colon).toLowerCase(Locale.ROOT);
            portText = entry.substring(colon + 1);
            if (!HOST.matcher(host).matches() || host.contains("..")) {
                throw invalid("invalid Kafka bootstrap host");
            }
        }
        int port = strictInteger(portText, "bootstrap port", 1, 65_535);
        String rendered = host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        return new HostPort(host, rendered);
    }

    private static boolean isLocalHost(String host) {
        if (host.contains(":")) {
            if (host.equals("::1")) {
                return true;
            }
            int separator = host.indexOf(':');
            if (separator == 0) {
                return false;
            }
            int firstGroup = Integer.parseInt(host.substring(0, separator), 16);
            return (firstGroup & 0xfe00) == 0xfc00 || (firstGroup & 0xffc0) == 0xfe80;
        }
        if (host.equals("localhost")) {
            return true;
        }
        String[] octets = host.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255 || !Integer.toString(value).equals(octet)) {
                    return false;
                }
            }
            return first == 10 || first == 127 || (first == 192 && second == 168)
                    || (first == 172 && second >= 16 && second <= 31);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validIpv6Literal(String host) {
        if (host.isEmpty() || host.length() > 39 || !host.matches("[0-9a-f:]+")
                || host.contains(":::")) {
            return false;
        }
        int compression = host.indexOf("::");
        if (compression != host.lastIndexOf("::")) {
            return false;
        }
        if (compression >= 0) {
            int leftGroups = ipv6GroupCount(host.substring(0, compression));
            int rightGroups = ipv6GroupCount(host.substring(compression + 2));
            return leftGroups >= 0 && rightGroups >= 0 && leftGroups + rightGroups < 8;
        }
        return ipv6GroupCount(host) == 8;
    }

    private static int ipv6GroupCount(String side) {
        if (side.isEmpty()) {
            return 0;
        }
        String[] groups = side.split(":", -1);
        for (String group : groups) {
            if (!group.matches("[0-9a-f]{1,4}")) {
                return -1;
            }
        }
        return groups.length;
    }

    private static void collectNested(String key,
                                      String value,
                                      String prefix,
                                      Set<String> allowedFields,
                                      Map<String, Map<String, String>> destination) {
        String remainder = key.substring(prefix.length());
        int separator = remainder.indexOf('.');
        if (separator < 1 || separator == remainder.length() - 1) {
            throw invalid("invalid Kafka executor setting");
        }
        String alias = requireAlias(remainder.substring(0, separator), "configuration alias");
        String field = remainder.substring(separator + 1);
        if (!allowedFields.contains(field)) {
            throw invalid("unknown Kafka executor setting");
        }
        Map<String, String> fields = destination.computeIfAbsent(alias, ignored -> new TreeMap<>());
        if (fields.put(field, value) != null) {
            throw invalid("duplicate Kafka executor setting");
        }
    }

    private static String requireKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 256 || !key.equals(key.trim())
                || hasControl(key)) {
            throw invalid("invalid Kafka executor setting key");
        }
        return key;
    }

    private static String requireRawValue(String value) {
        if (value == null || value.length() > MAX_SECRET_CHARACTERS || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw invalid("invalid or oversized Kafka executor value");
        }
        return value;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) {
            throw invalid("missing required Kafka executor setting: " + key);
        }
        return value;
    }

    private static String alias(Map<String, String> fields, String key) {
        return requireAlias(required(fields, key), key);
    }

    private static String requireAlias(String value, String name) {
        if (value == null || !ALIAS.matcher(value).matches()) {
            throw invalid("invalid Kafka " + name);
        }
        return value;
    }

    private static String exactText(String value, String name, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.trim()) || hasControl(value)) {
            throw invalid("invalid Kafka " + name);
        }
        return value;
    }

    private static String secret(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > MAX_SECRET_CHARACTERS
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("invalid Kafka secret setting: " + name);
        }
        return value;
    }

    private static Path absolutePath(String value, String name) {
        String text = exactText(value, name, MAX_PATH_CHARACTERS);
        try {
            Path path = Path.of(text);
            if (!path.isAbsolute() || !path.equals(path.normalize()) || path.getNameCount() == 0) {
                throw invalid("Kafka " + name + " must be an absolute normalized non-root path");
            }
            return path;
        } catch (InvalidPathException invalidPath) {
            throw invalid("invalid Kafka path setting: " + name);
        }
    }

    private static int integer(Map<String, String> fields,
                               String key,
                               int defaultValue,
                               int minimum,
                               int maximum) {
        return fields.containsKey(key)
                ? strictInteger(fields.get(key), key, minimum, maximum) : defaultValue;
    }

    private static int strictInteger(String value, String name, int minimum, int maximum) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,9}")) {
            throw invalid("invalid Kafka integer setting: " + name);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) {
                throw invalid("Kafka integer setting out of range: " + name);
            }
            return (int) parsed;
        } catch (NumberFormatException overflow) {
            throw invalid("invalid Kafka integer setting: " + name);
        }
    }

    private static boolean parseBoolean(String value, String name) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("Kafka boolean setting must be true or false: " + name);
        };
    }

    private static String storeType(String value) {
        String type = exactText(value, "store type", 16).toUpperCase(Locale.ROOT);
        if (!type.equals("JKS") && !type.equals("PKCS12")) {
            throw invalid("Kafka TLS store type must be JKS or PKCS12");
        }
        return type;
    }

    private static String jaasEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new java.util.HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static Set<String> union(Set<String> first, Set<String> second, Set<String> third) {
        return union(union(first, second), third);
    }

    private static void rejectAny(Map<String, String> fields, Set<String> keys, String profile) {
        if (keys.stream().anyMatch(fields::containsKey)) {
            throw invalid(profile + " does not accept settings from another security profile");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /** Supported fail-closed Kafka transport and authentication profiles. */
    public enum SecurityProfile {
        /** Development-only plaintext access to local or private bootstrap hosts. */
        LOCAL_DEMO("local-demo"),
        /** TLS server authentication, using JVM trust or an explicit truststore. */
        TLS("tls"),
        /** Mutual TLS with an explicit client keystore. */
        MTLS("mtls"),
        /** SASL authentication over TLS, limited to PLAIN or SCRAM. */
        SASL_TLS("sasl-tls");

        private final String configValue;

        SecurityProfile(String configValue) {
            this.configValue = configValue;
        }

        /**
         * Returns the exact configuration value.
         *
         * @return the lowercase profile identifier
         */
        public String configValue() {
            return configValue;
        }

        private static SecurityProfile parse(String value) {
            for (SecurityProfile profile : values()) {
                if (profile.configValue.equals(value)) {
                    return profile;
                }
            }
            throw invalid("unknown Kafka security-profile");
        }
    }

    /** One immutable Kafka connection/security profile selected by payload alias. */
    public static final class Target {
        private final String alias;
        private final String targetId;
        private final SecurityProfile securityProfile;
        private final Properties producerProperties;
        private final Duration acknowledgementTimeout;
        private final Duration closeTimeout;

        private Target(String alias,
                       String targetId,
                       SecurityProfile securityProfile,
                       Properties producerProperties,
                       Duration acknowledgementTimeout,
                       Duration closeTimeout) {
            this.alias = alias;
            this.targetId = targetId;
            this.securityProfile = securityProfile;
            this.producerProperties = copyProperties(producerProperties);
            this.acknowledgementTimeout = acknowledgementTimeout;
            this.closeTimeout = closeTimeout;
        }

        /**
         * Returns the payload-visible target alias.
         *
         * @return the target alias
         */
        public String alias() {
            return alias;
        }

        /**
         * Returns the immutable credential-free target identity.
         *
         * @return the target id
         */
        public String targetId() {
            return targetId;
        }

        /**
         * Returns the selected transport/authentication profile.
         *
         * @return the security profile
         */
        public SecurityProfile securityProfile() {
            return securityProfile;
        }

        /**
         * Returns a fresh validated producer property snapshot. It may contain
         * credentials and endpoints and therefore must never be logged.
         *
         * @return a mutable copy owned by one new producer
         * @hidden internal connector boundary
         */
        public Properties producerProperties() {
            return copyProperties(producerProperties);
        }

        /**
         * Returns the bounded synchronous acknowledgement timeout.
         *
         * @return the acknowledgement timeout
         */
        public Duration acknowledgementTimeout() {
            return acknowledgementTimeout;
        }

        /**
         * Returns the bounded producer close timeout.
         *
         * @return the close timeout
         */
        public Duration closeTimeout() {
            return closeTimeout;
        }

        @Override
        public String toString() {
            return "Target[alias=" + alias + ", targetId=" + targetId
                    + ", securityProfile=" + securityProfile.configValue + "]";
        }
    }

    /** One allowlisted payload topic alias bound to exactly one configured target. */
    public static final class Topic {
        private final String alias;
        private final String targetAlias;
        private final String physicalName;
        private final ConnectorTargetFingerprint destinationFingerprint;

        private Topic(String alias,
                      String targetAlias,
                      String physicalName,
                      ConnectorTargetFingerprint destinationFingerprint) {
            this.alias = alias;
            this.targetAlias = targetAlias;
            this.physicalName = physicalName;
            this.destinationFingerprint = destinationFingerprint;
        }

        /**
         * Returns the payload-visible topic alias.
         *
         * @return the topic alias
         */
        public String alias() {
            return alias;
        }

        /**
         * Returns the target alias to which this topic is bound.
         *
         * @return the target alias
         */
        public String targetAlias() {
            return targetAlias;
        }

        /**
         * Returns the allowlisted physical Kafka topic.
         *
         * @return the physical topic name
         */
        public String physicalName() {
            return physicalName;
        }

        /**
         * Returns the allowlisted physical Kafka topic.
         *
         * @return the physical topic name
         */
        public String name() {
            return physicalName;
        }

        /**
         * Returns the Phase 1.0 target-id/physical-topic commitment.
         *
         * @return the destination fingerprint
         */
        public ConnectorTargetFingerprint destinationFingerprint() {
            return destinationFingerprint;
        }

        @Override
        public String toString() {
            return "Topic[alias=" + alias + ", targetAlias=" + targetAlias + "]";
        }
    }

    /**
     * A command's fully allowlisted target/topic pair.
     *
     * @param target the validated target profile
     * @param topic the allowlisted topic bound to that target
     */
    public record ResolvedTopic(Target target, Topic topic) {
        /** Validates the resolved pair. */
        public ResolvedTopic {
            if (target == null || topic == null || !target.alias.equals(topic.targetAlias)) {
                throw invalid("invalid resolved Kafka topic");
            }
        }

        /**
         * Returns the Phase 1.0 target-id/physical-topic commitment.
         *
         * @return the destination fingerprint
         */
        public ConnectorTargetFingerprint fingerprint() {
            return topic.destinationFingerprint;
        }

        @Override
        public String toString() {
            return "ResolvedTopic[target=" + target.alias + ", topic=" + topic.alias + "]";
        }
    }

    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private record HostPort(String host, String rendered) {
    }
}
