package com.bloxbean.cardano.yano.appchain.ipfs.config;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.CanonicalCid;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsPinCommandV1;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.ipfs.IpfsV1Policy;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Strict parser for the stripped {@code effects.executors.ipfs.*} settings.
 * Commands select an immutable target profile by alias and can never supply a
 * provider URL or credential. Changing an endpoint, authentication profile,
 * codec policy, recursive policy, or replication policy requires a new target
 * id so receipts keep their original meaning.
 */
public final class IpfsEffectConfig {
    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+={0,16}");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("enabled", "detail-archive-path");
    private static final Set<String> TARGET_KEYS = Set.of(
            "target-id", "api-url", "security-profile", "allowed-codecs", "recursive",
            "replication-policy", "connect-timeout-ms", "request-timeout-ms",
            "close-timeout-ms", "bearer-token");

    private static final int MAX_SETTINGS = 256;
    private static final int MAX_TARGETS = 16;
    private static final int MAX_KEY_CHARACTERS = 512;
    private static final int MAX_VALUE_CHARACTERS = 4_096;
    private static final int MAX_URL_CHARACTERS = 2_048;
    private static final int MAX_BEARER_TOKEN_CHARACTERS = 2_048;
    private static final int MAX_PATH_CHARACTERS = 1_024;
    private static final int MAX_TIMEOUT_MS = 300_000;

    private final boolean enabled;
    private final Path detailArchivePath;
    private final Map<String, Target> targets;

    private IpfsEffectConfig(boolean enabled, Path detailArchivePath, Map<String, Target> targets) {
        this.enabled = enabled;
        this.detailArchivePath = detailArchivePath;
        this.targets = Map.copyOf(targets);
    }

    /**
     * Parses settings below {@code effects.executors.ipfs.}.
     *
     * @param settings stripped executor settings
     * @return an immutable, validated configuration
     */
    public static IpfsEffectConfig parse(Map<String, String> settings) {
        if (settings == null || settings.size() > MAX_SETTINGS) {
            throw invalid("IPFS settings must be present and bounded");
        }

        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = requireKey(entry.getKey());
            if (sorted.put(key, requireRawValue(entry.getValue())) != null) {
                throw invalid("duplicate IPFS executor setting");
            }
        }

        Map<String, String> topLevel = new TreeMap<>();
        Map<String, Map<String, String>> targetValues = new TreeMap<>();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String key = entry.getKey();
            if (TOP_LEVEL_KEYS.contains(key)) {
                topLevel.put(key, entry.getValue());
            } else if (key.startsWith("targets.")) {
                collectTarget(key, entry.getValue(), targetValues);
            } else {
                throw invalid("unknown IPFS executor setting");
            }
        }
        if (targetValues.size() > MAX_TARGETS) {
            throw invalid("too many IPFS targets");
        }

        boolean enabled = topLevel.containsKey("enabled")
                ? parseBoolean(topLevel.get("enabled"), "enabled") : !targetValues.isEmpty();
        Path detailArchivePath = topLevel.containsKey("detail-archive-path")
                ? absolutePath(topLevel.get("detail-archive-path")) : null;
        Map<String, Target> targets = parseTargets(targetValues);
        if (enabled && targets.isEmpty()) {
            throw invalid("enabled IPFS executor requires at least one target");
        }
        return new IpfsEffectConfig(enabled, detailArchivePath, targets);
    }

    /**
     * Reports whether the executor contribution is enabled.
     *
     * @return whether the executor contribution is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the optional durable detail-archive location.
     *
     * @return an absolute normalized non-root path when configured
     */
    public Optional<Path> detailArchivePath() {
        return Optional.ofNullable(detailArchivePath);
    }

    /**
     * Returns all immutable target profiles keyed by payload alias.
     *
     * @return immutable targets
     */
    public Map<String, Target> targets() {
        return targets;
    }

    /**
     * Looks up one target without accepting connection data from a payload.
     *
     * @param alias payload target alias
     * @return the configured target, if any
     */
    public Optional<Target> target(String alias) {
        return alias == null ? Optional.empty() : Optional.ofNullable(targets.get(alias));
    }

    /**
     * Resolves a validated command against the configured codec, recursive,
     * and replication policies. A missing target or policy mismatch is not
     * silently weakened and produces an empty result.
     *
     * @param command validated pin-only v1 command
     * @return the target only when every command assertion is allowlisted
     */
    public Optional<Target> resolve(IpfsPinCommandV1 command) {
        Objects.requireNonNull(command, "command");
        Target target = targets.get(command.target());
        if (target == null || !target.allowsCodec(command.cid().codec())
                || command.recursive() != target.recursive
                || (command.replicationPolicy() != null
                && !command.replicationPolicy().equals(target.replicationPolicy))) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * Returns a bounded diagnostic view. Provider URLs, bearer tokens, archive
     * paths, target ids, and replication-policy aliases are intentionally
     * omitted.
     *
     * @return an immutable redacted diagnostic snapshot
     */
    public Map<String, Object> safeDiagnostics() {
        Map<String, String> securityProfiles = new TreeMap<>();
        Map<String, List<String>> codecs = new TreeMap<>();
        Map<String, Boolean> recursive = new TreeMap<>();
        Map<String, Boolean> authentication = new TreeMap<>();
        targets.forEach((alias, target) -> {
            securityProfiles.put(alias, target.securityProfile.configValue);
            codecs.put(alias, target.codecNames);
            recursive.put(alias, target.recursive);
            authentication.put(alias, target.bearerToken != null);
        });

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("enabled", enabled);
        diagnostics.put("targetAliases", List.copyOf(new TreeMap<>(targets).keySet()));
        diagnostics.put("securityProfiles", Map.copyOf(securityProfiles));
        diagnostics.put("allowedCodecs", Map.copyOf(codecs));
        diagnostics.put("recursive", Map.copyOf(recursive));
        diagnostics.put("authenticationConfigured", Map.copyOf(authentication));
        diagnostics.put("detailArchiveConfigured", detailArchivePath != null);
        return Map.copyOf(diagnostics);
    }

    @Override
    public String toString() {
        return "IpfsEffectConfig" + safeDiagnostics();
    }

    private static Map<String, Target> parseTargets(Map<String, Map<String, String>> values) {
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> targetIds = new HashSet<>();
        for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
            String targetAlias = entry.getKey();
            Map<String, String> fields = entry.getValue();
            String targetId = requireAlias(required(fields, "target-id"), "target-id");
            if (!targetIds.add(targetId)) {
                throw invalid("duplicate IPFS target-id");
            }

            URI apiUrl = apiUrl(required(fields, "api-url"));
            SecurityProfile securityProfile = SecurityProfile.parse(required(fields,
                    "security-profile"));
            CodecPolicy codecs = codecPolicy(required(fields, "allowed-codecs"));
            boolean recursive = parseBoolean(required(fields, "recursive"), "recursive");
            String replicationPolicy = requireAlias(required(fields, "replication-policy"),
                    "replication-policy");
            Duration connectTimeout = timeout(fields, "connect-timeout-ms");
            Duration requestTimeout = timeout(fields, "request-timeout-ms");
            Duration closeTimeout = timeout(fields, "close-timeout-ms");
            if (connectTimeout.compareTo(requestTimeout) > 0
                    || closeTimeout.compareTo(requestTimeout) > 0) {
                throw invalid("IPFS connect and close timeouts must not exceed request timeout");
            }

            String bearerToken = fields.get("bearer-token");
            validateSecurityProfile(securityProfile, apiUrl, bearerToken);
            if (bearerToken != null) {
                bearerToken = bearerToken(bearerToken);
            }
            targets.put(targetAlias, new Target(targetAlias, targetId, apiUrl, securityProfile,
                    codecs.values, codecs.names, recursive, replicationPolicy, connectTimeout,
                    requestTimeout, closeTimeout, bearerToken));
        }
        return Map.copyOf(targets);
    }

    private static void collectTarget(String key,
                                      String value,
                                      Map<String, Map<String, String>> targets) {
        String remainder = key.substring("targets.".length());
        int separator = remainder.indexOf('.');
        if (separator < 1 || separator == remainder.length() - 1
                || remainder.indexOf('.', separator + 1) >= 0) {
            throw invalid("invalid IPFS executor setting");
        }
        String alias = requireAlias(remainder.substring(0, separator), "target alias");
        String field = remainder.substring(separator + 1);
        if (!TARGET_KEYS.contains(field)) {
            throw invalid("unknown IPFS executor setting");
        }
        Map<String, String> fields = targets.computeIfAbsent(alias, ignored -> new TreeMap<>());
        if (fields.put(field, value) != null) {
            throw invalid("duplicate IPFS executor setting");
        }
    }

    private static URI apiUrl(String value) {
        String exact = exactText(value, "api-url", MAX_URL_CHARACTERS);
        try {
            URI uri = new URI(exact);
            String scheme = uri.getScheme();
            String rawHost = uri.getHost();
            if (scheme == null || rawHost == null || (!scheme.equals("http")
                    && !scheme.equals("https")) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65_535
                    || rawHost.contains("%")) {
                throw invalid("invalid IPFS api-url");
            }

            String host = unbracketed(rawHost).toLowerCase(Locale.ROOT);
            String canonicalHost = canonicalHost(host);
            URI canonical = new URI(scheme, null, canonicalHost, uri.getPort(), null, null, null);
            if (!canonical.toASCIIString().equals(exact)) {
                throw invalid("IPFS api-url must use canonical origin syntax");
            }
            return canonical;
        } catch (URISyntaxException syntax) {
            throw invalid("invalid IPFS api-url");
        }
    }

    private static String canonicalHost(String host) {
        if (host.indexOf(':') >= 0) {
            int[] groups = parseIpv6(host);
            return renderIpv6(groups);
        }
        if (looksLikeIpv4(host)) {
            return canonicalIpv4(host);
        }
        if (host.isEmpty() || host.length() > 253 || host.endsWith(".")) {
            throw invalid("invalid IPFS api-url host");
        }
        for (String label : host.split("\\.", -1)) {
            if (!DNS_LABEL.matcher(label).matches()) {
                throw invalid("invalid IPFS api-url host");
            }
        }
        return host;
    }

    private static void validateSecurityProfile(SecurityProfile profile,
                                                URI apiUrl,
                                                String token) {
        String host = unbracketed(apiUrl.getHost());
        if (profile == SecurityProfile.LOCAL_DEMO) {
            if (!apiUrl.getScheme().equals("http") || !isLocalLiteralOrLocalhost(host)) {
                throw invalid("local-demo IPFS targets require a local numeric HTTP origin");
            }
            if (token != null) {
                throw invalid("local-demo IPFS targets do not accept bearer authentication");
            }
            return;
        }
        if (!apiUrl.getScheme().equals("https")) {
            throw invalid("bearer-tls IPFS targets require HTTPS");
        }
        if (token == null) {
            throw invalid("bearer-tls IPFS targets require bearer-token");
        }
        bearerToken(token);
    }

    private static boolean isLocalLiteralOrLocalhost(String host) {
        String value = unbracketed(host);
        if (value.equals("localhost")) {
            return true;
        }
        if (value.indexOf(':') >= 0) {
            int[] groups = parseIpv6(value);
            boolean loopback = true;
            for (int index = 0; index < groups.length - 1; index++) {
                loopback &= groups[index] == 0;
            }
            loopback &= groups[7] == 1;
            return loopback || (groups[0] & 0xfe00) == 0xfc00;
        }
        if (!looksLikeIpv4(value)) {
            return false;
        }
        String[] octets = value.split("\\.", -1);
        int first = strictIpv4Octet(octets[0]);
        int second = strictIpv4Octet(octets[1]);
        strictIpv4Octet(octets[2]);
        strictIpv4Octet(octets[3]);
        return first == 10 || first == 127 || (first == 192 && second == 168)
                || (first == 172 && second >= 16 && second <= 31);
    }

    private static boolean looksLikeIpv4(String host) {
        return host.chars().allMatch(character -> character == '.' || Character.isDigit(character));
    }

    private static String canonicalIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            throw invalid("invalid IPFS IPv4 address");
        }
        List<String> canonical = new ArrayList<>(4);
        for (String octet : octets) {
            canonical.add(Integer.toString(strictIpv4Octet(octet)));
        }
        return String.join(".", canonical);
    }

    private static int strictIpv4Octet(String value) {
        if (value.isEmpty() || value.length() > 3) {
            throw invalid("invalid IPFS IPv4 address");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 255 || !Integer.toString(parsed).equals(value)) {
                throw invalid("invalid IPFS IPv4 address");
            }
            return parsed;
        } catch (NumberFormatException invalidNumber) {
            throw invalid("invalid IPFS IPv4 address");
        }
    }

    private static int[] parseIpv6(String value) {
        if (!value.equals(value.toLowerCase(Locale.ROOT)) || value.indexOf('.') >= 0
                || value.isEmpty() || value.startsWith(":") && !value.startsWith("::")
                || value.endsWith(":") && !value.endsWith("::")) {
            throw invalid("invalid IPFS IPv6 address");
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) {
            throw invalid("invalid IPFS IPv6 address");
        }

        String leftText = compression < 0 ? value : value.substring(0, compression);
        String rightText = compression < 0 ? "" : value.substring(compression + 2);
        List<Integer> left = parseIpv6Groups(leftText);
        List<Integer> right = parseIpv6Groups(rightText);
        int zeros = 8 - left.size() - right.size();
        if ((compression < 0 && zeros != 0) || (compression >= 0 && zeros < 1)) {
            throw invalid("invalid IPFS IPv6 address");
        }

        int[] groups = new int[8];
        int offset = 0;
        for (int group : left) {
            groups[offset++] = group;
        }
        offset += zeros;
        for (int group : right) {
            groups[offset++] = group;
        }
        return groups;
    }

    private static List<Integer> parseIpv6Groups(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<Integer> groups = new ArrayList<>();
        for (String group : text.split(":", -1)) {
            if (group.isEmpty() || group.length() > 4
                    || !group.chars().allMatch(character -> character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f')) {
                throw invalid("invalid IPFS IPv6 address");
            }
            groups.add(Integer.parseInt(group, 16));
        }
        return groups;
    }

    private static String renderIpv6(int[] groups) {
        int bestStart = -1;
        int bestLength = 0;
        for (int index = 0; index < groups.length; ) {
            if (groups[index] != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < groups.length && groups[end] == 0) {
                end++;
            }
            int length = end - index;
            if (length > bestLength && length >= 2) {
                bestStart = index;
                bestLength = length;
            }
            index = end;
        }

        StringBuilder result = new StringBuilder();
        for (int index = 0; index < groups.length; ) {
            if (index == bestStart) {
                result.append("::");
                index += bestLength;
                continue;
            }
            if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') {
                result.append(':');
            }
            result.append(Integer.toHexString(groups[index++]));
        }
        return result.toString();
    }

    private static String unbracketed(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private static CodecPolicy codecPolicy(String value) {
        return switch (value) {
            case "raw" -> new CodecPolicy(Set.of(IpfsV1Policy.RAW_CODEC), List.of("raw"));
            case "dag-pb" -> new CodecPolicy(Set.of(CanonicalCid.DAG_PB_CODEC), List.of("dag-pb"));
            case "raw,dag-pb" -> {
                Set<Long> codecs = new LinkedHashSet<>();
                codecs.add(IpfsV1Policy.RAW_CODEC);
                codecs.add(CanonicalCid.DAG_PB_CODEC);
                yield new CodecPolicy(Collections.unmodifiableSet(codecs),
                        List.of("raw", "dag-pb"));
            }
            default -> throw invalid("invalid or non-canonical IPFS allowed-codecs");
        };
    }

    private static Duration timeout(Map<String, String> fields, String key) {
        String value = required(fields, key);
        if (!value.matches("[1-9][0-9]{0,8}")) {
            throw invalid("invalid IPFS integer setting: " + key);
        }
        try {
            int milliseconds = Integer.parseInt(value);
            if (milliseconds > MAX_TIMEOUT_MS) {
                throw invalid("IPFS integer setting out of range: " + key);
            }
            return Duration.ofMillis(milliseconds);
        } catch (NumberFormatException overflow) {
            throw invalid("invalid IPFS integer setting: " + key);
        }
    }

    private static String bearerToken(String value) {
        if (value.length() > MAX_BEARER_TOKEN_CHARACTERS
                || !BEARER_TOKEN.matcher(value).matches()) {
            throw invalid("invalid IPFS bearer-token");
        }
        return value;
    }

    private static String requireKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARACTERS
                || !key.equals(key.trim()) || hasControl(key)) {
            throw invalid("invalid IPFS executor setting key");
        }
        return key;
    }

    private static String requireRawValue(String value) {
        if (value == null || value.length() > MAX_VALUE_CHARACTERS || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw invalid("invalid or oversized IPFS executor value");
        }
        return value;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) {
            throw invalid("missing required IPFS executor setting: " + key);
        }
        return value;
    }

    private static String requireAlias(String value, String name) {
        if (value == null || !ALIAS.matcher(value).matches()) {
            throw invalid("invalid IPFS " + name);
        }
        return value;
    }

    private static String exactText(String value, String name, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.trim()) || hasControl(value) || hasNonAscii(value)) {
            throw invalid("invalid IPFS " + name);
        }
        return value;
    }

    private static Path absolutePath(String value) {
        String exact = exactText(value, "detail-archive-path", MAX_PATH_CHARACTERS);
        try {
            Path path = Path.of(exact);
            if (!path.isAbsolute() || !path.equals(path.normalize()) || path.getNameCount() == 0) {
                throw invalid("IPFS detail-archive-path must be an absolute normalized non-root path");
            }
            return path;
        } catch (InvalidPathException invalidPath) {
            throw invalid("invalid IPFS detail-archive-path");
        }
    }

    private static boolean parseBoolean(String value, String name) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("IPFS boolean setting must be true or false: " + name);
        };
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static boolean hasNonAscii(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character > 0x7e);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /** Supported IPFS transport and authentication profiles. */
    public enum SecurityProfile {
        /** Plain HTTP restricted to exact localhost or local numeric addresses. */
        LOCAL_DEMO("local-demo"),
        /** TLS with a bounded explicit bearer token. */
        BEARER_TLS("bearer-tls");

        private final String configValue;

        SecurityProfile(String configValue) {
            this.configValue = configValue;
        }

        /**
         * Returns the exact serialized value.
         *
         * @return lowercase configuration value
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
            throw invalid("unknown IPFS security-profile");
        }
    }

    /** One immutable IPFS target selected by a payload alias. */
    public static final class Target {
        private final String alias;
        private final String targetId;
        private final URI apiUrl;
        private final SecurityProfile securityProfile;
        private final Set<Long> allowedCodecs;
        private final List<String> codecNames;
        private final boolean recursive;
        private final String replicationPolicy;
        private final Duration connectTimeout;
        private final Duration requestTimeout;
        private final Duration closeTimeout;
        private final String bearerToken;
        private final ConnectorTargetFingerprint targetFingerprint;

        private Target(String alias,
                       String targetId,
                       URI apiUrl,
                       SecurityProfile securityProfile,
                       Set<Long> allowedCodecs,
                       List<String> codecNames,
                       boolean recursive,
                       String replicationPolicy,
                       Duration connectTimeout,
                       Duration requestTimeout,
                       Duration closeTimeout,
                       String bearerToken) {
            this.alias = alias;
            this.targetId = targetId;
            this.apiUrl = apiUrl;
            this.securityProfile = securityProfile;
            this.allowedCodecs = Set.copyOf(allowedCodecs);
            this.codecNames = List.copyOf(codecNames);
            this.recursive = recursive;
            this.replicationPolicy = replicationPolicy;
            this.connectTimeout = connectTimeout;
            this.requestTimeout = requestTimeout;
            this.closeTimeout = closeTimeout;
            this.bearerToken = bearerToken;
            this.targetFingerprint = IpfsTargetFingerprint.compute(targetId);
        }

        /**
         * Returns the payload-visible target alias.
         *
         * @return payload-visible target alias
         */
        public String alias() {
            return alias;
        }

        /**
         * Returns the versioned credential-free identity.
         *
         * @return target identity
         */
        public String targetId() {
            return targetId;
        }

        /**
         * Returns the canonical API origin.
         *
         * @return canonical API origin
         */
        public URI apiUrl() {
            return apiUrl;
        }

        /**
         * Returns the transport and authentication profile.
         *
         * @return security profile
         */
        public SecurityProfile securityProfile() {
            return securityProfile;
        }

        /**
         * Returns the allowed CID codecs.
         *
         * @return immutable codec values
         */
        public Set<Long> allowedCodecs() {
            return allowedCodecs;
        }

        /**
         * Checks whether a canonical CID codec is allowlisted.
         *
         * @param codec unsigned multicodec value
         * @return whether this target accepts the codec
         */
        public boolean allowsCodec(long codec) {
            return allowedCodecs.contains(codec);
        }

        /**
         * Returns the required recursive-pin behavior.
         *
         * @return recursive-pin policy
         */
        public boolean recursive() {
            return recursive;
        }

        /**
         * Returns the configured replication-policy alias.
         *
         * @return replication policy
         */
        public String replicationPolicy() {
            return replicationPolicy;
        }

        /**
         * Returns the maximum connection duration.
         *
         * @return connection timeout
         */
        public Duration connectTimeout() {
            return connectTimeout;
        }

        /**
         * Returns the maximum provider-request duration.
         *
         * @return request timeout
         */
        public Duration requestTimeout() {
            return requestTimeout;
        }

        /**
         * Returns the maximum client-close duration.
         *
         * @return close timeout
         */
        public Duration closeTimeout() {
            return closeTimeout;
        }

        /**
         * Returns the bearer credential for bearer-TLS.
         *
         * @return optional bearer credential
         */
        public Optional<String> bearerToken() {
            return Optional.ofNullable(bearerToken);
        }

        /**
         * Returns the credential-free target commitment.
         *
         * @return target fingerprint
         */
        public ConnectorTargetFingerprint targetFingerprint() {
            return targetFingerprint;
        }

        @Override
        public String toString() {
            return "Target{alias=" + alias + ", securityProfile=" + securityProfile.configValue
                    + ", allowedCodecs=" + codecNames + ", recursive=" + recursive
                    + ", authenticationConfigured=" + (bearerToken != null) + '}';
        }
    }

    private record CodecPolicy(Set<Long> values, List<String> names) {
    }
}
