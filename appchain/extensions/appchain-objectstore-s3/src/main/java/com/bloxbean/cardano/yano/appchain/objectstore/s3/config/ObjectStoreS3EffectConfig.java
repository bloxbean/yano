package com.bloxbean.cardano.yano.appchain.objectstore.s3.config;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorTargetFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.detail.ObjectRetentionMode;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectDestinationFingerprint;
import com.bloxbean.cardano.yano.appchain.integration.objectstore.ObjectPutCommandV1;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Strict parser for the stripped {@code effects.executors.objectstore-s3.*}
 * settings. The parser exposes provider-neutral values only; AWS SDK types stay
 * behind the connector's internal client boundary. Target, encryption-policy,
 * and retention-policy ids are versioned identities: operators must issue a new
 * id rather than repoint one after changing a destination, KMS key, encryption
 * mode, retention mode, retention duration, or managed-AWS expected bucket
 * owner.
 */
public final class ObjectStoreS3EffectConfig {
    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern REGION = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern OBJECT_SEGMENT = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._~!$'()+,;=@-]{0,127}");
    private static final Pattern ACCESS_KEY = Pattern.compile("[A-Za-z0-9._/@+=:-]{1,256}");
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern KMS_KEY = Pattern.compile("[A-Za-z0-9:/_.+=,@-]{1,2048}");
    private static final Pattern AWS_ACCOUNT_ID = Pattern.compile("[0-9]{12}");
    private static final Pattern AWS_KMS_KEY_ARN = Pattern.compile(
            "arn:(?:aws|aws-us-gov|aws-cn):kms:([a-z0-9-]{1,63}):[0-9]{12}:"
                    + "key/[A-Za-z0-9-]{1,128}");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("enabled", "detail-archive-path");
    private static final Set<String> TARGET_KEYS = Set.of(
            "target-id", "endpoint", "region", "security-profile", "path-style",
            "credentials-provider", "credentials.access-key-id", "credentials.secret-access-key",
            "credentials.session-token", "credentials.profile-name",
            "source-bucket", "source-prefix", "source-expected-owner",
            "destination-bucket", "destination-prefix", "destination-expected-owner",
            "encryption-policy-id", "encryption-mode", "kms-key-id",
            "retention-policy-id", "require-versioning", "max-object-bytes",
            "api-call-timeout-ms", "api-call-attempt-timeout-ms", "connect-timeout-ms",
            "socket-timeout-ms", "close-timeout-ms");
    private static final Set<String> RETENTION_CLASS_KEYS = Set.of("mode", "days");
    private static final Set<String> CREDENTIAL_KEYS = Set.of(
            "credentials.access-key-id", "credentials.secret-access-key",
            "credentials.session-token", "credentials.profile-name");

    private static final int MAX_SETTINGS = 512;
    private static final int MAX_TARGETS = 16;
    private static final int MAX_RETENTION_CLASSES = 32;
    private static final int MAX_VALUE_CHARACTERS = 4_096;
    private static final int MAX_PATH_CHARACTERS = 1_024;
    private static final int MAX_OBJECT_KEY_BYTES = 512;
    private static final int MAX_PROVIDER_OBJECT_KEY_BYTES = 1_024;
    private static final int MAX_RETENTION_DAYS = 36_500;

    private static final int DEFAULT_MAX_OBJECT_BYTES = 16 * 1024 * 1024;
    private static final int DEFAULT_API_CALL_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_API_CALL_ATTEMPT_TIMEOUT_MS = 20_000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_CLOSE_TIMEOUT_MS = 5_000;

    private final boolean enabled;
    private final Path detailArchivePath;
    private final Map<String, Target> targets;

    private ObjectStoreS3EffectConfig(boolean enabled,
                                      Path detailArchivePath,
                                      Map<String, Target> targets) {
        this.enabled = enabled;
        this.detailArchivePath = detailArchivePath;
        this.targets = Map.copyOf(targets);
    }

    /**
     * Parses settings below {@code effects.executors.objectstore-s3.}.
     *
     * @param settings stripped executor settings
     * @return an immutable, validated configuration
     */
    public static ObjectStoreS3EffectConfig parse(Map<String, String> settings) {
        if (settings == null || settings.size() > MAX_SETTINGS) {
            throw invalid("object-store settings must be present and bounded");
        }

        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String key = requireKey(entry.getKey());
            if (sorted.put(key, requireRawValue(entry.getValue())) != null) {
                throw invalid("duplicate object-store executor setting");
            }
        }

        Map<String, String> topLevel = new TreeMap<>();
        Map<String, TargetValues> targetValues = new TreeMap<>();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            String key = entry.getKey();
            if (TOP_LEVEL_KEYS.contains(key)) {
                topLevel.put(key, entry.getValue());
            } else if (key.startsWith("targets.")) {
                collectTarget(key, entry.getValue(), targetValues);
            } else {
                throw invalid("unknown object-store executor setting");
            }
        }
        if (targetValues.size() > MAX_TARGETS) {
            throw invalid("too many object-store targets");
        }

        boolean enabled = topLevel.containsKey("enabled")
                ? parseBoolean(topLevel.get("enabled"), "enabled") : !targetValues.isEmpty();
        Path detailArchivePath = topLevel.containsKey("detail-archive-path")
                ? absolutePath(topLevel.get("detail-archive-path"), "detail-archive-path") : null;
        Map<String, Target> targets = parseTargets(targetValues);
        if (enabled && targets.isEmpty()) {
            throw invalid("enabled object-store executor requires at least one target");
        }
        return new ObjectStoreS3EffectConfig(enabled, detailArchivePath, targets);
    }

    /**
     * Reports whether the executor contribution is configured and enabled.
     *
     * @return whether the executor contribution is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the configured durable detail-archive location.
     *
     * @return the optional absolute durable detail-archive path
     */
    public Optional<Path> detailArchivePath() {
        return Optional.ofNullable(detailArchivePath);
    }

    /**
     * Returns all immutable target profiles keyed by payload alias.
     *
     * @return all configured target profiles
     */
    public Map<String, Target> targets() {
        return targets;
    }

    /**
     * Looks up one configured profile without accepting an endpoint from a
     * command payload.
     *
     * @param alias payload target alias
     * @return the configured target, if present
     */
    public Optional<Target> target(String alias) {
        return alias == null ? Optional.empty() : Optional.ofNullable(targets.get(alias));
    }

    /**
     * Resolves a validated command to configured source/destination keys and
     * policy. The retention deadline is based on the supplied mutation time,
     * truncated to the millisecond precision carried by connector details.
     *
     * <p>An empty result means that the target or requested retention class is
     * not allowlisted. Size-policy violations fail closed.</p>
     *
     * @param command validated v1 command
     * @param executionTime external mutation time, not consensus time
     * @return an immutable resolved operation, or empty for an unknown alias
     */
    public Optional<ResolvedObject> resolve(ObjectPutCommandV1 command, Instant executionTime) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(executionTime, "executionTime");
        Target target = targets.get(command.target());
        if (target == null) {
            return Optional.empty();
        }
        if (command.size() > target.maxObjectBytes) {
            throw invalid("object exceeds the configured target size limit");
        }

        Optional<ResolvedRetention> retention = Optional.empty();
        if (command.retentionClass() != null) {
            RetentionClass retentionClass = target.retentionClasses.get(command.retentionClass());
            if (retentionClass == null) {
                return Optional.empty();
            }
            retention = Optional.of(new ResolvedRetention(retentionClass,
                    retentionClass.retainUntil(executionTime)));
        }
        return Optional.of(new ResolvedObject(target,
                target.composeSourceKey(command.sourceKey()),
                target.composeDestinationKey(command.destinationKey()),
                target.destinationFingerprint(command.destinationKey()),
                retention));
    }

    /**
     * Returns a bounded diagnostic view containing aliases, profiles, and
     * booleans only. Endpoints, regions, buckets, prefixes, paths, credential
     * material, KMS identifiers, and policy internals are omitted.
     *
     * @return an immutable redacted diagnostic snapshot
     */
    public Map<String, Object> safeDiagnostics() {
        Map<String, String> securityProfiles = new TreeMap<>();
        Map<String, Boolean> customEndpoints = new TreeMap<>();
        Map<String, Boolean> pathStyle = new TreeMap<>();
        Map<String, Boolean> versioning = new TreeMap<>();
        targets.forEach((alias, target) -> {
            securityProfiles.put(alias, target.securityProfile.configValue);
            customEndpoints.put(alias, target.endpoint.isPresent());
            pathStyle.put(alias, target.pathStyle);
            versioning.put(alias, target.requireVersioning);
        });

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("enabled", enabled);
        diagnostics.put("targetAliases", List.copyOf(new TreeMap<>(targets).keySet()));
        diagnostics.put("securityProfiles", Map.copyOf(securityProfiles));
        diagnostics.put("customEndpointConfigured", Map.copyOf(customEndpoints));
        diagnostics.put("pathStyle", Map.copyOf(pathStyle));
        diagnostics.put("versioningRequired", Map.copyOf(versioning));
        diagnostics.put("detailArchiveConfigured", detailArchivePath != null);
        return Map.copyOf(diagnostics);
    }

    @Override
    public String toString() {
        return "ObjectStoreS3EffectConfig" + safeDiagnostics();
    }

    private static Map<String, Target> parseTargets(Map<String, TargetValues> values) {
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> targetIds = new java.util.HashSet<>();
        for (Map.Entry<String, TargetValues> entry : values.entrySet()) {
            String alias = entry.getKey();
            TargetValues value = entry.getValue();
            Map<String, String> fields = value.fields;
            String targetId = alias(fields, "target-id");
            if (!targetIds.add(targetId)) {
                throw invalid("duplicate object-store target-id");
            }

            SecurityProfile securityProfile = SecurityProfile.parse(required(fields,
                    "security-profile"));
            Optional<URI> endpoint = optionalEndpoint(fields.get("endpoint"));
            String region = region(required(fields, "region"));
            boolean pathStyle = parseBoolean(required(fields, "path-style"), "path-style");
            Credentials credentials = credentials(fields);
            validateConnectionProfile(securityProfile, endpoint, pathStyle, credentials);

            String sourceBucket = bucket(required(fields, "source-bucket"), "source-bucket");
            String sourcePrefix = optionalPrefix(fields.get("source-prefix"), "source-prefix");
            String destinationBucket = bucket(required(fields, "destination-bucket"),
                    "destination-bucket");
            String destinationPrefix = optionalPrefix(fields.get("destination-prefix"),
                    "destination-prefix");
            ExpectedBucketOwners expectedOwners = expectedBucketOwners(fields, endpoint,
                    sourceBucket, destinationBucket);

            String encryptionPolicyId = alias(fields, "encryption-policy-id");
            Encryption encryption = encryption(fields, securityProfile, endpoint, region);
            String retentionPolicyId = alias(fields, "retention-policy-id");
            Map<String, RetentionClass> retentionClasses = retentionClasses(value.retentionClasses);

            boolean requireVersioning = parseBoolean(required(fields, "require-versioning"),
                    "require-versioning");
            if (!requireVersioning) {
                throw invalid("object-store destination versioning is mandatory");
            }
            int maxObjectBytes = integer(fields, "max-object-bytes", DEFAULT_MAX_OBJECT_BYTES,
                    1, DEFAULT_MAX_OBJECT_BYTES);
            Timeouts timeouts = timeouts(fields);

            targets.put(alias, new Target(alias, targetId, endpoint, region, securityProfile,
                    pathStyle, credentials, sourceBucket, sourcePrefix,
                    expectedOwners.source(), destinationBucket, destinationPrefix,
                    expectedOwners.destination(), encryptionPolicyId, encryption,
                    retentionPolicyId, retentionClasses, true, maxObjectBytes, timeouts));
        }
        return Map.copyOf(targets);
    }

    private static ExpectedBucketOwners expectedBucketOwners(Map<String, String> fields,
                                                              Optional<URI> endpoint,
                                                              String sourceBucket,
                                                              String destinationBucket) {
        String source = fields.get("source-expected-owner");
        String destination = fields.get("destination-expected-owner");
        if (endpoint.isPresent()) {
            if (source != null || destination != null) {
                throw invalid("custom object-store endpoints do not accept expected bucket owners");
            }
            return new ExpectedBucketOwners(null, null);
        }

        source = awsAccountId(required(fields, "source-expected-owner"),
                "source-expected-owner");
        destination = awsAccountId(required(fields, "destination-expected-owner"),
                "destination-expected-owner");
        if (sourceBucket.equals(destinationBucket) && !source.equals(destination)) {
            throw invalid("the same source and destination bucket must have one expected owner");
        }
        return new ExpectedBucketOwners(source, destination);
    }

    private static Credentials credentials(Map<String, String> fields) {
        CredentialsProvider provider = CredentialsProvider.parse(required(fields,
                "credentials-provider"));
        String accessKey = fields.get("credentials.access-key-id");
        String secretKey = fields.get("credentials.secret-access-key");
        String sessionToken = fields.get("credentials.session-token");
        String profileName = fields.get("credentials.profile-name");

        return switch (provider) {
            case STATIC -> {
                rejectPresent(profileName, "static credentials do not accept a profile name");
                yield new Credentials(provider,
                        accessKey(required(fields, "credentials.access-key-id")),
                        secret(required(fields, "credentials.secret-access-key"),
                                "credentials.secret-access-key"),
                        sessionToken == null ? null : secret(sessionToken,
                                "credentials.session-token"), null);
            }
            case ENVIRONMENT, DEFAULT -> {
                rejectCredentialMaterial(fields, provider.configValue +
                        " credentials do not accept explicit credential fields");
                yield new Credentials(provider, null, null, null, null);
            }
            case PROFILE -> {
                if (accessKey != null || secretKey != null || sessionToken != null) {
                    throw invalid("profile credentials do not accept static credential fields");
                }
                yield new Credentials(provider, null, null, null,
                        profileName(required(fields, "credentials.profile-name")));
            }
        };
    }

    private static Encryption encryption(Map<String, String> fields,
                                         SecurityProfile securityProfile,
                                         Optional<URI> endpoint,
                                         String region) {
        EncryptionMode mode = EncryptionMode.parse(required(fields, "encryption-mode"));
        String kmsKeyId = fields.get("kms-key-id");
        if (mode == EncryptionMode.NONE) {
            if (securityProfile != SecurityProfile.LOCAL_DEMO) {
                throw invalid("unencrypted object storage is limited to local-demo targets");
            }
            rejectPresent(kmsKeyId, "encryption mode none does not accept a KMS key");
            return new Encryption(mode, null);
        }
        if (mode == EncryptionMode.SSE_S3) {
            rejectPresent(kmsKeyId, "sse-s3 does not accept a KMS key");
            return new Encryption(mode, null);
        }
        kmsKeyId = kmsKey(required(fields, "kms-key-id"));
        if (endpoint.isEmpty()) {
            var matcher = AWS_KMS_KEY_ARN.matcher(kmsKeyId);
            if (!matcher.matches() || !region.equals(matcher.group(1))) {
                throw invalid("managed AWS sse-kms requires a full key ARN in the target region");
            }
        }
        return new Encryption(mode, kmsKeyId);
    }

    private static Map<String, RetentionClass> retentionClasses(
            Map<String, Map<String, String>> values) {
        if (values.size() > MAX_RETENTION_CLASSES) {
            throw invalid("too many object-store retention classes");
        }
        Map<String, RetentionClass> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
            Map<String, String> fields = entry.getValue();
            ObjectRetentionMode mode = retentionMode(required(fields, "mode"));
            int days = strictInteger(required(fields, "days"), "retention days", 1,
                    MAX_RETENTION_DAYS);
            result.put(entry.getKey(), new RetentionClass(entry.getKey(), mode, days));
        }
        return Map.copyOf(result);
    }

    private static Timeouts timeouts(Map<String, String> fields) {
        int apiCall = integer(fields, "api-call-timeout-ms", DEFAULT_API_CALL_TIMEOUT_MS,
                1_000, 120_000);
        int attempt = integer(fields, "api-call-attempt-timeout-ms",
                DEFAULT_API_CALL_ATTEMPT_TIMEOUT_MS, 500, 60_000);
        int connect = integer(fields, "connect-timeout-ms", DEFAULT_CONNECT_TIMEOUT_MS,
                100, 30_000);
        int socket = integer(fields, "socket-timeout-ms", DEFAULT_SOCKET_TIMEOUT_MS,
                100, 60_000);
        int close = integer(fields, "close-timeout-ms", DEFAULT_CLOSE_TIMEOUT_MS,
                100, 30_000);
        if (attempt > apiCall) {
            throw invalid("object-store attempt timeout must not exceed API timeout");
        }
        if (connect > attempt || socket > attempt) {
            throw invalid("object-store transport timeouts must not exceed attempt timeout");
        }
        return new Timeouts(Duration.ofMillis(apiCall), Duration.ofMillis(attempt),
                Duration.ofMillis(connect), Duration.ofMillis(socket), Duration.ofMillis(close));
    }

    private static void validateConnectionProfile(SecurityProfile securityProfile,
                                                  Optional<URI> endpoint,
                                                  boolean pathStyle,
                                                  Credentials credentials) {
        if (securityProfile == SecurityProfile.LOCAL_DEMO) {
            if (endpoint.isEmpty() || !"http".equals(endpoint.orElseThrow().getScheme())
                    || !isLocalHost(endpoint.orElseThrow().getHost())) {
                throw invalid("local-demo object storage requires localhost or a private "
                        + "numeric HTTP endpoint");
            }
            if (!pathStyle) {
                throw invalid("local-demo object storage requires path-style addressing");
            }
            if (credentials.provider != CredentialsProvider.STATIC) {
                throw invalid("local-demo object storage requires explicit static credentials");
            }
            return;
        }

        if (endpoint.isPresent()) {
            if (!"https".equals(endpoint.orElseThrow().getScheme())) {
                throw invalid("custom TLS object-store endpoints must use HTTPS");
            }
            if (credentials.provider != CredentialsProvider.STATIC) {
                throw invalid("custom object-store endpoints require explicit static credentials");
            }
        } else if (pathStyle) {
            throw invalid("managed AWS targets require virtual-host addressing");
        }
    }

    private static void collectTarget(String key,
                                      String value,
                                      Map<String, TargetValues> targetValues) {
        String remainder = key.substring("targets.".length());
        int separator = remainder.indexOf('.');
        if (separator < 1 || separator == remainder.length() - 1) {
            throw invalid("invalid object-store executor setting");
        }
        String targetAlias = requireAlias(remainder.substring(0, separator),
                "target alias");
        String field = remainder.substring(separator + 1);
        TargetValues target = targetValues.computeIfAbsent(targetAlias,
                ignored -> new TargetValues());
        if (field.startsWith("retention-classes.")) {
            collectRetentionClass(field, value, target.retentionClasses);
            return;
        }
        if (!TARGET_KEYS.contains(field)) {
            throw invalid("unknown object-store executor setting");
        }
        if (target.fields.put(field, value) != null) {
            throw invalid("duplicate object-store executor setting");
        }
    }

    private static void collectRetentionClass(String field,
                                              String value,
                                              Map<String, Map<String, String>> classes) {
        String remainder = field.substring("retention-classes.".length());
        int separator = remainder.indexOf('.');
        if (separator < 1 || separator == remainder.length() - 1
                || remainder.indexOf('.', separator + 1) >= 0) {
            throw invalid("invalid object-store retention-class setting");
        }
        String alias = requireAlias(remainder.substring(0, separator), "retention class alias");
        String classField = remainder.substring(separator + 1);
        if (!RETENTION_CLASS_KEYS.contains(classField)) {
            throw invalid("unknown object-store retention-class setting");
        }
        Map<String, String> values = classes.computeIfAbsent(alias, ignored -> new TreeMap<>());
        if (values.put(classField, value) != null) {
            throw invalid("duplicate object-store retention-class setting");
        }
    }

    private static Optional<URI> optionalEndpoint(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String exact = exactText(value, "endpoint", 2_048);
        try {
            URI uri = new URI(exact);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || (!scheme.equals("http") && !scheme.equals("https"))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getRawPath() != null
                    && !uri.getRawPath().isEmpty()) || host.contains("%")
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65_535) {
                throw invalid("invalid object-store endpoint");
            }
            String canonicalHost = host.toLowerCase(Locale.ROOT);
            URI canonical = new URI(scheme, null, canonicalHost, uri.getPort(), null, null, null);
            if (!canonical.toASCIIString().equals(exact)) {
                throw invalid("object-store endpoint must use canonical scheme and host syntax");
            }
            return Optional.of(canonical);
        } catch (URISyntaxException syntax) {
            throw invalid("invalid object-store endpoint");
        }
    }

    private static boolean isLocalHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.contains(":")) {
            if (value.equals("::1")) {
                return true;
            }
            int separator = value.indexOf(':');
            if (separator <= 0) {
                return false;
            }
            try {
                int firstGroup = Integer.parseInt(value.substring(0, separator), 16);
                return (firstGroup & 0xfe00) == 0xfc00;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        if (value.equals("localhost")) {
            return true;
        }
        if (!value.contains(".")) {
            return false;
        }
        String[] octets = value.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = strictIpv4Octet(octets[0]);
            int second = strictIpv4Octet(octets[1]);
            strictIpv4Octet(octets[2]);
            strictIpv4Octet(octets[3]);
            return first == 10 || first == 127 || (first == 192 && second == 168)
                    || (first == 172 && second >= 16 && second <= 31);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static int strictIpv4Octet(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0 || parsed > 255 || !Integer.toString(parsed).equals(value)) {
            throw invalid("invalid IPv4 octet");
        }
        return parsed;
    }

    private static String composeKey(String prefix, String relativeKey) {
        String key = objectKey(relativeKey, "relative object key");
        String composed = prefix.isEmpty() ? key : prefix + "/" + key;
        if (composed.length() > MAX_PROVIDER_OBJECT_KEY_BYTES) {
            throw invalid("composed object-store key exceeds the provider limit");
        }
        return composed;
    }

    private static String optionalPrefix(String value, String name) {
        return value == null || value.isEmpty() ? "" : objectKey(value, name);
    }

    private static String objectKey(String value, String name) {
        if (value == null || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('%') >= 0
                || value.length() < 1 || value.length() > MAX_OBJECT_KEY_BYTES
                || hasNonAscii(value)) {
            throw invalid("invalid object-store " + name);
        }
        String[] segments = value.split("/", -1);
        if (segments.length > 32) {
            throw invalid("invalid object-store " + name);
        }
        for (String segment : segments) {
            if (segment.equals(".") || segment.equals("..")
                    || !OBJECT_SEGMENT.matcher(segment).matches()) {
                throw invalid("invalid object-store " + name);
            }
        }
        return value;
    }

    private static String bucket(String value, String name) {
        String exact = exactText(value, name, 255);
        if (!BUCKET.matcher(exact).matches() || exact.equals(".") || exact.equals("..")) {
            throw invalid("invalid object-store " + name);
        }
        return exact;
    }

    private static String region(String value) {
        String exact = exactText(value, "region", 63);
        if (!REGION.matcher(exact).matches()) {
            throw invalid("invalid object-store region");
        }
        return exact;
    }

    private static String accessKey(String value) {
        if (!ACCESS_KEY.matcher(value).matches()) {
            throw invalid("invalid object-store static access-key id");
        }
        return value;
    }

    private static String profileName(String value) {
        if (!PROFILE_NAME.matcher(value).matches()) {
            throw invalid("invalid object-store credentials profile name");
        }
        return value;
    }

    private static String kmsKey(String value) {
        if (!KMS_KEY.matcher(value).matches()) {
            throw invalid("invalid object-store KMS key id");
        }
        return value;
    }

    private static String awsAccountId(String value, String name) {
        String exact = exactText(value, name, 12);
        if (!AWS_ACCOUNT_ID.matcher(exact).matches()) {
            throw invalid("invalid object-store " + name);
        }
        return exact;
    }

    private static ObjectRetentionMode retentionMode(String value) {
        return switch (value) {
            case "governance" -> ObjectRetentionMode.GOVERNANCE;
            case "compliance" -> ObjectRetentionMode.COMPLIANCE;
            default -> throw invalid("unknown object-store retention mode");
        };
    }

    private static String requireKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 512 || !key.equals(key.trim())
                || hasControl(key)) {
            throw invalid("invalid object-store executor setting key");
        }
        return key;
    }

    private static String requireRawValue(String value) {
        if (value == null || value.length() > MAX_VALUE_CHARACTERS || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw invalid("invalid or oversized object-store executor value");
        }
        return value;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) {
            throw invalid("missing required object-store executor setting: " + key);
        }
        return value;
    }

    private static String alias(Map<String, String> fields, String key) {
        return requireAlias(required(fields, key), key);
    }

    private static String requireAlias(String value, String name) {
        if (value == null || !ALIAS.matcher(value).matches()) {
            throw invalid("invalid object-store " + name);
        }
        return value;
    }

    private static String exactText(String value, String name, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.trim()) || hasControl(value) || hasNonAscii(value)) {
            throw invalid("invalid object-store " + name);
        }
        return value;
    }

    private static String secret(String value, String name) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_CHARACTERS
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("invalid object-store secret setting: " + name);
        }
        return value;
    }

    private static Path absolutePath(String value, String name) {
        String exact = exactText(value, name, MAX_PATH_CHARACTERS);
        try {
            Path path = Path.of(exact);
            if (!path.isAbsolute() || !path.equals(path.normalize()) || path.getNameCount() == 0) {
                throw invalid("object-store " + name
                        + " must be an absolute normalized non-root path");
            }
            return path;
        } catch (InvalidPathException invalidPath) {
            throw invalid("invalid object-store path setting: " + name);
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
            throw invalid("invalid object-store integer setting: " + name);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) {
                throw invalid("object-store integer setting out of range: " + name);
            }
            return (int) parsed;
        } catch (NumberFormatException overflow) {
            throw invalid("invalid object-store integer setting: " + name);
        }
    }

    private static boolean parseBoolean(String value, String name) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("object-store boolean setting must be true or false: " + name);
        };
    }

    private static void rejectCredentialMaterial(Map<String, String> fields, String message) {
        if (CREDENTIAL_KEYS.stream().anyMatch(fields::containsKey)) {
            throw invalid(message);
        }
    }

    private static void rejectPresent(String value, String message) {
        if (value != null) {
            throw invalid(message);
        }
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

    /** Supported transport profiles. */
    public enum SecurityProfile {
        /** Plain HTTP restricted to local/private demo-compatible endpoints. */
        LOCAL_DEMO("local-demo"),
        /** TLS for managed AWS or an explicitly configured compatible endpoint. */
        TLS("tls");

        private final String configValue;

        SecurityProfile(String configValue) {
            this.configValue = configValue;
        }

        /**
         * Returns the serialized configuration value.
         *
         * @return exact lowercase configuration value
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
            throw invalid("unknown object-store security-profile");
        }
    }

    /** Supported provider-neutral credential source profiles. */
    public enum CredentialsProvider {
        /** Explicit bounded credentials, required for custom endpoints. */
        STATIC("static"),
        /** Standard environment credential variables, managed AWS only. */
        ENVIRONMENT("environment"),
        /** One named shared-configuration profile, managed AWS only. */
        PROFILE("profile"),
        /** The SDK's bounded default provider chain, managed AWS only. */
        DEFAULT("default");

        private final String configValue;

        CredentialsProvider(String configValue) {
            this.configValue = configValue;
        }

        /**
         * Returns the serialized configuration value.
         *
         * @return exact lowercase configuration value
         */
        public String configValue() {
            return configValue;
        }

        private static CredentialsProvider parse(String value) {
            for (CredentialsProvider provider : values()) {
                if (provider.configValue.equals(value)) {
                    return provider;
                }
            }
            throw invalid("unknown object-store credentials-provider");
        }
    }

    /** Supported server-side encryption request modes. */
    public enum EncryptionMode {
        /** No requested provider encryption; local-demo only. */
        NONE("none"),
        /** Provider-managed S3 server-side encryption. */
        SSE_S3("sse-s3"),
        /** KMS server-side encryption with one configured key id. */
        SSE_KMS("sse-kms");

        private final String configValue;

        EncryptionMode(String configValue) {
            this.configValue = configValue;
        }

        /**
         * Returns the serialized configuration value.
         *
         * @return exact lowercase configuration value
         */
        public String configValue() {
            return configValue;
        }

        private static EncryptionMode parse(String value) {
            for (EncryptionMode mode : values()) {
                if (mode.configValue.equals(value)) {
                    return mode;
                }
            }
            throw invalid("unknown object-store encryption-mode");
        }
    }

    /** Provider-neutral credential selection and material. */
    public static final class Credentials {
        private final CredentialsProvider provider;
        private final String accessKeyId;
        private final String secretAccessKey;
        private final String sessionToken;
        private final String profileName;

        private Credentials(CredentialsProvider provider,
                            String accessKeyId,
                            String secretAccessKey,
                            String sessionToken,
                            String profileName) {
            this.provider = provider;
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.sessionToken = sessionToken;
            this.profileName = profileName;
        }

        /**
         * Returns the selected credential source.
         *
         * @return selected credential source
         */
        public CredentialsProvider provider() {
            return provider;
        }

        /**
         * Returns the configured static access-key identifier.
         *
         * @return static access-key id when configured
         */
        public Optional<String> accessKeyId() {
            return Optional.ofNullable(accessKeyId);
        }

        /**
         * Returns the configured static secret access key.
         *
         * @return static secret access key when configured
         */
        public Optional<String> secretAccessKey() {
            return Optional.ofNullable(secretAccessKey);
        }

        /**
         * Returns the configured static session token.
         *
         * @return optional static session token
         */
        public Optional<String> sessionToken() {
            return Optional.ofNullable(sessionToken);
        }

        /**
         * Returns the selected shared-configuration profile name.
         *
         * @return shared-configuration profile name when selected
         */
        public Optional<String> profileName() {
            return Optional.ofNullable(profileName);
        }

        @Override
        public String toString() {
            return "Credentials{provider=" + provider.configValue
                    + ", explicitMaterialConfigured=" + (accessKeyId != null) + '}';
        }
    }

    /** Provider-neutral server-side encryption policy. */
    public static final class Encryption {
        private final EncryptionMode mode;
        private final String kmsKeyId;

        private Encryption(EncryptionMode mode, String kmsKeyId) {
            this.mode = mode;
            this.kmsKeyId = kmsKeyId;
        }

        /**
         * Returns the selected encryption request mode.
         *
         * @return selected request mode
         */
        public EncryptionMode mode() {
            return mode;
        }

        /**
         * Returns the configured KMS key identifier.
         *
         * @return configured KMS key id only for {@link EncryptionMode#SSE_KMS}
         */
        public Optional<String> kmsKeyId() {
            return Optional.ofNullable(kmsKeyId);
        }

        @Override
        public String toString() {
            return "Encryption{mode=" + mode.configValue
                    + ", kmsKeyConfigured=" + (kmsKeyId != null) + '}';
        }
    }

    /**
     * Bounded operation and transport timeouts.
     *
     * @param apiCall maximum duration of an entire provider API call
     * @param apiCallAttempt maximum duration of one provider API-call attempt
     * @param connect maximum transport connection duration
     * @param socket maximum blocking socket duration
     * @param close maximum client-close duration
     */
    public record Timeouts(Duration apiCall,
                           Duration apiCallAttempt,
                           Duration connect,
                           Duration socket,
                           Duration close) {
        /** Validates non-null durations produced by the parser. */
        public Timeouts {
            Objects.requireNonNull(apiCall, "apiCall");
            Objects.requireNonNull(apiCallAttempt, "apiCallAttempt");
            Objects.requireNonNull(connect, "connect");
            Objects.requireNonNull(socket, "socket");
            Objects.requireNonNull(close, "close");
        }
    }

    /** One allowlisted WORM retention class. */
    public static final class RetentionClass {
        private final String alias;
        private final ObjectRetentionMode mode;
        private final int days;

        private RetentionClass(String alias, ObjectRetentionMode mode, int days) {
            this.alias = alias;
            this.mode = mode;
            this.days = days;
        }

        /**
         * Returns the payload-visible retention-class alias.
         *
         * @return retention-class alias
         */
        public String alias() {
            return alias;
        }

        /**
         * Returns the configured object-lock mode.
         *
         * @return governance or compliance retention mode
         */
        public ObjectRetentionMode mode() {
            return mode;
        }

        /**
         * Returns the configured retention duration.
         *
         * @return configured whole 24-hour retention days
         */
        public int days() {
            return days;
        }

        /**
         * Computes the request deadline from the actual external mutation time.
         * Millisecond truncation matches the detail contract and provider
         * reconciliation compares the stored exact deadline rather than
         * recomputing it on a retry.
         *
         * @param executionTime time immediately before the external mutation
         * @return execution time plus the configured count of exact 24-hour days
         */
        public Instant retainUntil(Instant executionTime) {
            Objects.requireNonNull(executionTime, "executionTime");
            try {
                Instant millis = Instant.ofEpochMilli(executionTime.toEpochMilli());
                return millis.plus(days, ChronoUnit.DAYS);
            } catch (ArithmeticException | DateTimeException overflow) {
                throw invalid("object-store retention deadline is outside Instant range");
            }
        }

        @Override
        public String toString() {
            return "RetentionClass{alias=" + alias + ", mode=" + mode + '}';
        }
    }

    /** One immutable object-store target selected by payload alias. */
    public static final class Target {
        private final String alias;
        private final String targetId;
        private final Optional<URI> endpoint;
        private final String region;
        private final SecurityProfile securityProfile;
        private final boolean pathStyle;
        private final Credentials credentials;
        private final String sourceBucket;
        private final String sourcePrefix;
        private final String sourceExpectedOwner;
        private final String destinationBucket;
        private final String destinationPrefix;
        private final String destinationExpectedOwner;
        private final String encryptionPolicyId;
        private final Encryption encryption;
        private final String retentionPolicyId;
        private final Map<String, RetentionClass> retentionClasses;
        private final boolean requireVersioning;
        private final int maxObjectBytes;
        private final Timeouts timeouts;

        private Target(String alias,
                       String targetId,
                       Optional<URI> endpoint,
                       String region,
                       SecurityProfile securityProfile,
                       boolean pathStyle,
                       Credentials credentials,
                       String sourceBucket,
                       String sourcePrefix,
                       String sourceExpectedOwner,
                       String destinationBucket,
                       String destinationPrefix,
                       String destinationExpectedOwner,
                       String encryptionPolicyId,
                       Encryption encryption,
                       String retentionPolicyId,
                       Map<String, RetentionClass> retentionClasses,
                       boolean requireVersioning,
                       int maxObjectBytes,
                       Timeouts timeouts) {
            this.alias = alias;
            this.targetId = targetId;
            this.endpoint = endpoint;
            this.region = region;
            this.securityProfile = securityProfile;
            this.pathStyle = pathStyle;
            this.credentials = credentials;
            this.sourceBucket = sourceBucket;
            this.sourcePrefix = sourcePrefix;
            this.sourceExpectedOwner = sourceExpectedOwner;
            this.destinationBucket = destinationBucket;
            this.destinationPrefix = destinationPrefix;
            this.destinationExpectedOwner = destinationExpectedOwner;
            this.encryptionPolicyId = encryptionPolicyId;
            this.encryption = encryption;
            this.retentionPolicyId = retentionPolicyId;
            this.retentionClasses = Map.copyOf(retentionClasses);
            this.requireVersioning = requireVersioning;
            this.maxObjectBytes = maxObjectBytes;
            this.timeouts = timeouts;
        }

        /**
         * Returns the payload-visible target alias.
         *
         * @return target alias
         */
        public String alias() {
            return alias;
        }

        /**
         * Returns the immutable target identity.
         *
         * @return immutable credential-free target identity
         */
        public String targetId() {
            return targetId;
        }

        /**
         * Returns the custom S3-compatible endpoint.
         *
         * @return custom compatible endpoint, empty for managed AWS
         */
        public Optional<URI> endpoint() {
            return endpoint;
        }

        /**
         * Returns the provider signing and service region.
         *
         * @return signing and service region
         */
        public String region() {
            return region;
        }

        /**
         * Returns the selected transport-security profile.
         *
         * @return selected transport profile
         */
        public SecurityProfile securityProfile() {
            return securityProfile;
        }

        /**
         * Reports whether path-style bucket addressing is enabled.
         *
         * @return whether path-style addressing is explicitly enabled
         */
        public boolean pathStyle() {
            return pathStyle;
        }

        /**
         * Returns the provider-neutral credential selection.
         *
         * @return configured credential selection
         */
        public Credentials credentials() {
            return credentials;
        }

        /**
         * Returns the allowlisted source bucket.
         *
         * @return source bucket
         */
        public String sourceBucket() {
            return sourceBucket;
        }

        /**
         * Returns the normalized source-key prefix.
         *
         * @return normalized source prefix, or empty
         */
        public String sourcePrefix() {
            return sourcePrefix;
        }

        /**
         * Returns the exact managed-AWS account expected to own the source
         * bucket. This value is deliberately omitted from diagnostics and
         * string rendering. Changing it requires a new {@link #targetId()}.
         *
         * @return expected 12-digit AWS account id, empty for a custom endpoint
         */
        public Optional<String> sourceExpectedOwner() {
            return Optional.ofNullable(sourceExpectedOwner);
        }

        /**
         * Returns the allowlisted immutable destination bucket.
         *
         * @return destination bucket
         */
        public String destinationBucket() {
            return destinationBucket;
        }

        /**
         * Returns the normalized destination-key prefix.
         *
         * @return normalized destination prefix, or empty
         */
        public String destinationPrefix() {
            return destinationPrefix;
        }

        /**
         * Returns the exact managed-AWS account expected to own the destination
         * bucket. This value is deliberately omitted from diagnostics and
         * string rendering. Changing it requires a new {@link #targetId()}.
         *
         * @return expected 12-digit AWS account id, empty for a custom endpoint
         */
        public Optional<String> destinationExpectedOwner() {
            return Optional.ofNullable(destinationExpectedOwner);
        }

        /**
         * Returns the immutable encryption-policy identity.
         *
         * @return encryption-policy identity
         */
        public String encryptionPolicyId() {
            return encryptionPolicyId;
        }

        /**
         * Returns the provider-neutral encryption request policy.
         *
         * @return encryption request policy
         */
        public Encryption encryption() {
            return encryption;
        }

        /**
         * Returns the immutable retention-policy identity.
         *
         * @return retention-policy identity
         */
        public String retentionPolicyId() {
            return retentionPolicyId;
        }

        /**
         * Returns all allowlisted retention classes by alias.
         *
         * @return configured retention classes
         */
        public Map<String, RetentionClass> retentionClasses() {
            return retentionClasses;
        }

        /**
         * Looks up one allowlisted retention class.
         *
         * @param classAlias payload-visible retention-class alias
         * @return an allowlisted retention class, if configured
         */
        public Optional<RetentionClass> retentionClass(String classAlias) {
            return classAlias == null ? Optional.empty()
                    : Optional.ofNullable(retentionClasses.get(classAlias));
        }

        /**
         * Reports whether destination-bucket versioning is required.
         *
         * @return always true for a valid v1 target
         */
        public boolean requireVersioning() {
            return requireVersioning;
        }

        /**
         * Returns the target-specific object-size ceiling.
         *
         * @return maximum object size in bytes
         */
        public int maxObjectBytes() {
            return maxObjectBytes;
        }

        /**
         * Returns the bounded operation and transport timeouts.
         *
         * @return configured timeouts
         */
        public Timeouts timeouts() {
            return timeouts;
        }

        /**
         * Composes the provider source key from its prefix and relative key.
         *
         * @param relativeKey validated command-relative source key
         * @return prefix and relative source key joined by exactly one slash
         */
        public String composeSourceKey(String relativeKey) {
            return composeKey(sourcePrefix, relativeKey);
        }

        /**
         * Composes the provider destination key from its prefix and relative key.
         *
         * @param relativeKey validated command-relative destination key
         * @return prefix and relative destination key joined by exactly one slash
         */
        public String composeDestinationKey(String relativeKey) {
            return composeKey(destinationPrefix, relativeKey);
        }

        /**
         * Computes the frozen credential-free destination fingerprint using the
         * relative command key, not the composed provider key.
         *
         * @param destinationRelativeKey validated command-relative key
         * @return domain-separated destination fingerprint
         */
        public ConnectorTargetFingerprint destinationFingerprint(String destinationRelativeKey) {
            String key = objectKey(destinationRelativeKey, "destination relative key");
            return ObjectDestinationFingerprint.compute(targetId, destinationBucket,
                    destinationPrefix, key, encryptionPolicyId, retentionPolicyId);
        }

        @Override
        public String toString() {
            return "Target{alias=" + alias + ", targetId=" + targetId
                    + ", securityProfile=" + securityProfile.configValue
                    + ", customEndpointConfigured=" + endpoint.isPresent()
                    + ", pathStyle=" + pathStyle
                    + ", versioningRequired=" + requireVersioning + '}';
        }
    }

    /**
     * One resolved WORM retention request.
     *
     * @param retentionClass configured retention-class policy
     * @param retainUntil exact external retention deadline
     */
    public record ResolvedRetention(RetentionClass retentionClass, Instant retainUntil) {
        /** Validates parser-created resolved retention. */
        public ResolvedRetention {
            Objects.requireNonNull(retentionClass, "retentionClass");
            Objects.requireNonNull(retainUntil, "retainUntil");
        }
    }

    /** Resolved operation containing no SDK-specific values. */
    public static final class ResolvedObject {
        private final Target target;
        private final String sourceObjectKey;
        private final String destinationObjectKey;
        private final ConnectorTargetFingerprint destinationFingerprint;
        private final Optional<ResolvedRetention> retention;

        private ResolvedObject(Target target,
                               String sourceObjectKey,
                               String destinationObjectKey,
                               ConnectorTargetFingerprint destinationFingerprint,
                               Optional<ResolvedRetention> retention) {
            this.target = target;
            this.sourceObjectKey = sourceObjectKey;
            this.destinationObjectKey = destinationObjectKey;
            this.destinationFingerprint = destinationFingerprint;
            this.retention = retention;
        }

        /**
         * Returns the selected target.
         *
         * @return selected target
         */
        public Target target() {
            return target;
        }

        /**
         * Returns the fully composed provider source key.
         *
         * @return composed source key
         */
        public String sourceObjectKey() {
            return sourceObjectKey;
        }

        /**
         * Returns the fully composed provider destination key.
         *
         * @return composed destination key
         */
        public String destinationObjectKey() {
            return destinationObjectKey;
        }

        /**
         * Returns the credential-free destination commitment.
         *
         * @return destination fingerprint
         */
        public ConnectorTargetFingerprint destinationFingerprint() {
            return destinationFingerprint;
        }

        /**
         * Returns the requested WORM retention policy.
         *
         * @return requested retention, empty when the command selected none
         */
        public Optional<ResolvedRetention> retention() {
            return retention;
        }

        @Override
        public String toString() {
            return "ResolvedObject{targetAlias=" + target.alias
                    + ", retentionConfigured=" + retention.isPresent() + '}';
        }
    }

    private static final class TargetValues {
        private final Map<String, String> fields = new TreeMap<>();
        private final Map<String, Map<String, String>> retentionClasses = new TreeMap<>();
    }

    private record ExpectedBucketOwners(String source, String destination) {
    }
}
