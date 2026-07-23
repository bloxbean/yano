package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Idempotent, SigV4-authenticated RustFS IAM bootstrap with bounded I/O. */
final class RustFsAdminBootstrapper {
    private static final int MAX_RESPONSE_BYTES = 65_536;
    private static final int READY_ATTEMPTS = 120;
    private static final int MAX_TIMESTAMP_LENGTH = 64;
    private static final Set<String> POLICY_RESPONSE_FIELDS = Set.of(
            "policy_name", "policy", "create_date", "update_date");
    private static final Set<String> USER_RESPONSE_FIELDS = Set.of(
            "policyName", "status", "updatedAt");
    private static final Set<String> POLICY_FIELDS = Set.of("ID", "Version", "Statement");
    private static final Set<String> STATEMENT_FIELDS = Set.of(
            "Sid", "Effect", "Action", "NotAction", "Resource", "NotResource", "Condition");
    private static final ProxySelector NO_PROXY = new ProxySelector() {
        @Override
        public List<Proxy> select(java.net.URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(java.net.URI uri, SocketAddress address, IOException failure) {
            // No proxy is selected and provider details are never reflected.
        }
    };

    private final S3BootstrapConfig config;
    private final RustFsIamSpec spec;
    private final HttpClient http;
    private final Aws4Signer signer = Aws4Signer.create();
    private final Aws4SignerParams signerParams;

    RustFsAdminBootstrapper(S3BootstrapConfig config) {
        this.config = config;
        this.spec = RustFsIamSpec.load(config.iamSpecFile());
        if (!config.accessKey().reveal().equals(spec.bootstrap().accessKey())) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(NO_PROXY)
                .build();
        this.signerParams = Aws4SignerParams.builder()
                .awsCredentials(AwsBasicCredentials.create(
                        config.accessKey().reveal(), config.secretKey().reveal()))
                .signingName("s3")
                .signingRegion(Region.of(config.region()))
                .doubleUrlEncode(false)
                .normalizePath(true)
                .build();
    }

    void bootstrap() {
        waitUntilReady();
        for (Map.Entry<String, String> policy : spec.policies().entrySet()) {
            requireSuccessAndDiscard(send(SdkHttpMethod.PUT, "/rustfs/admin/v3/add-canned-policy",
                    Map.of("name", policy.getKey()),
                    policy.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        provision(spec.runner(), config.runnerSecretKey());
        provision(spec.executor(), config.executorSecretKey());
        for (Map.Entry<String, String> policy : spec.policies().entrySet()) {
            verifyPolicy(policy.getKey(), policy.getValue());
        }
        verifyUser(spec.runner());
        verifyUser(spec.executor());
        verifyPrincipalInventory();
    }

    private void provision(RustFsIamSpec.Role role, SecretValue secret) {
        byte[] body;
        try {
            body = StrictJson.MAPPER.writeValueAsBytes(Map.of(
                    "secretKey", secret.reveal(), "status", "enabled"));
        } catch (IOException failure) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
        try {
            requireSuccessAndDiscard(send(SdkHttpMethod.PUT, "/rustfs/admin/v3/add-user",
                    Map.of("accessKey", role.accessKey(), "status", "enabled"), body));
        } finally {
            Arrays.fill(body, (byte) 0);
        }
        requireSuccessAndDiscard(send(SdkHttpMethod.PUT, "/rustfs/admin/v3/set-user-or-group-policy",
                Map.of("policyName", role.policyName(), "userOrGroup", role.accessKey(),
                        "isGroup", "false"), new byte[0]));
    }

    private void verifyPolicy(String policyName, String expectedPolicy) {
        Response response = send(SdkHttpMethod.GET, "/rustfs/admin/v3/info-canned-policy",
                Map.of("name", policyName), new byte[0]);
        try {
            requireSuccess(response);
            validatePolicyResponse(response.body(), policyName, expectedPolicy);
        } finally {
            response.clear();
        }
    }

    private void verifyUser(RustFsIamSpec.Role role) {
        Response response = send(SdkHttpMethod.GET, "/rustfs/admin/v3/user-info",
                Map.of("accessKey", role.accessKey()), new byte[0]);
        try {
            requireSuccess(response);
            validateUserResponse(response.body(), role.policyName());
        } finally {
            response.clear();
        }
    }

    private void verifyPrincipalInventory() {
        Response users = send(SdkHttpMethod.GET, "/rustfs/admin/v3/list-users", Map.of(), new byte[0]);
        try {
            requireSuccess(users);
            validateUserInventory(users.body(), spec.runner(), spec.executor());
        } finally {
            users.clear();
        }
        Response response = send(SdkHttpMethod.GET, "/rustfs/admin/v3/list-access-keys-bulk",
                Map.of("all", "true", "listType", "all"), new byte[0]);
        try {
            requireSuccess(response);
            validateAccessKeyInventory(response.body(), Set.of(
                    spec.bootstrap().accessKey(), spec.runner().accessKey(), spec.executor().accessKey()));
        } finally {
            response.clear();
        }
    }

    private void waitUntilReady() {
        java.net.URI ready = config.endpoint().resolve("/health/ready");
        for (int attempt = 0; attempt < READY_ATTEMPTS; attempt++) {
            try {
                HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(ready)
                                .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try (InputStream body = response.body()) {
                    bytes = readBounded(body);
                }
                try {
                    if (response.statusCode() == 200) {
                        return;
                    }
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            } catch (IOException ignored) {
                // Startup races are expected; the fixed deadline remains authoritative.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DemoException(DemoError.SERVICE_TIMEOUT);
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DemoException(DemoError.SERVICE_TIMEOUT);
            }
        }
        throw new DemoException(DemoError.SERVICE_TIMEOUT);
    }

    private Response send(SdkHttpMethod method, String path, Map<String, String> query, byte[] body) {
        try {
            String payloadHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
            ContentStreamProvider provider = () -> new ByteArrayInputStream(body);
            SdkHttpFullRequest.Builder request = SdkHttpFullRequest.builder()
                    .uri(config.endpoint())
                    .encodedPath(path)
                    .method(method)
                    .putHeader("content-type", "application/json")
                    .putHeader("x-amz-content-sha256", payloadHash)
                    .contentStreamProvider(provider);
            query.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> request.putRawQueryParameter(entry.getKey(), entry.getValue()));
            SdkHttpFullRequest signed = signer.sign(request.build(), signerParams);
            HttpRequest.Builder outbound = HttpRequest.newBuilder(signed.getUri())
                    .timeout(Duration.ofSeconds(15));
            signed.headers().forEach((name, values) -> {
                if (!"host".equalsIgnoreCase(name) && !"content-length".equalsIgnoreCase(name)) {
                    values.forEach(value -> outbound.header(name, value));
                }
            });
            outbound.method(method.name(), body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<InputStream> response = http.send(outbound.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                return new Response(response.statusCode(), readBounded(input));
            }
        } catch (DemoException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DemoException(DemoError.SERVICE_TIMEOUT);
        } catch (Exception failure) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return bytes;
    }

    private static void requireSuccess(Response response) {
        if (response.status() < 200 || response.status() >= 300) {
            throw new DemoException(DemoError.INITIALIZATION_FAILED);
        }
    }

    private static void requireSuccessAndDiscard(Response response) {
        try {
            requireSuccess(response);
        } finally {
            response.clear();
        }
    }

    static void validatePolicyResponse(byte[] bytes, String policyName, String expectedPolicy) {
        JsonNode root = StrictJson.parse(bytes);
        Set<String> actual = fields(root);
        if (!root.isObject() || !actual.containsAll(Set.of("policy_name", "policy"))
                || !POLICY_RESPONSE_FIELDS.containsAll(actual)
                || !policyName.equals(root.path("policy_name").textValue())
                || !canonicalPolicy(root.path("policy")).equals(canonicalPolicy(
                StrictJson.parse(expectedPolicy.getBytes(StandardCharsets.UTF_8))))) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        validateOptionalPinnedTimeText(root, "create_date");
        validateOptionalPinnedTimeText(root, "update_date");
    }

    static void validateUserResponse(byte[] bytes, String policyName) {
        validateUserNode(StrictJson.parse(bytes), policyName);
    }

    static void validateUserInventory(
            byte[] bytes,
            RustFsIamSpec.Role runner,
            RustFsIamSpec.Role executor) {
        JsonNode root = StrictJson.parse(bytes);
        if (!root.isObject() || !fields(root).equals(Set.of(runner.accessKey(), executor.accessKey()))) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        validateUserNode(root.path(runner.accessKey()), runner.policyName());
        validateUserNode(root.path(executor.accessKey()), executor.policyName());
    }

    static void validateAccessKeyInventory(byte[] bytes, Set<String> expectedUsers) {
        JsonNode root = StrictJson.parse(bytes);
        if (!root.isObject() || !fields(root).equals(expectedUsers)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        root.elements().forEachRemaining(inventory -> {
            if (!inventory.isObject()
                    || !fields(inventory).equals(Set.of("serviceAccounts", "stsKeys"))
                    || !inventory.path("serviceAccounts").isArray()
                    || !inventory.path("serviceAccounts").isEmpty()
                    || !inventory.path("stsKeys").isArray()
                    || !inventory.path("stsKeys").isEmpty()) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        });
    }

    private static void validateUserNode(JsonNode root, String policyName) {
        if (!root.isObject() || !fields(root).equals(USER_RESPONSE_FIELDS)
                || !policyName.equals(root.path("policyName").textValue())
                || !"enabled".equals(root.path("status").textValue())) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        validateRequiredTimestamp(root, "updatedAt");
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(fields::add);
        }
        return fields;
    }

    private static void validateOptionalPinnedTimeText(JsonNode root, String field) {
        if (root.has(field)) {
            String value = root.path(field).textValue();
            // RustFS beta.8 serializes these OffsetDateTime values through
            // time's human-readable serde representation rather than RFC3339.
            // They are informational; policy identity and content are exact.
            if (value == null || value.isBlank() || value.length() > MAX_TIMESTAMP_LENGTH
                    || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        }
    }

    private static void validateRequiredTimestamp(JsonNode root, String field) {
        String value = root.path(field).textValue();
        if (value == null || value.isBlank() || value.length() > MAX_TIMESTAMP_LENGTH) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException failure) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static PolicySemantic canonicalPolicy(JsonNode policy) {
        requireAllowedFields(policy, POLICY_FIELDS, Set.of("Version", "Statement"));
        String id = policy.has("ID") ? requiredText(policy, "ID", true) : "";
        String version = requiredText(policy, "Version", false);
        JsonNode statements = policy.path("Statement");
        if (!statements.isArray() || statements.isEmpty() || statements.size() > 32) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        List<StatementSemantic> canonical = new ArrayList<>();
        statements.forEach(statement -> canonical.add(canonicalStatement(statement)));
        return new PolicySemantic(id, version, List.copyOf(canonical));
    }

    private static StatementSemantic canonicalStatement(JsonNode statement) {
        requireAllowedFields(statement, STATEMENT_FIELDS, Set.of("Sid", "Effect"));
        String sid = requiredText(statement, "Sid", false);
        String effect = requiredText(statement, "Effect", false);
        if (!Set.of("Allow", "Deny").contains(effect)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        FieldSet actions = exactlyOneSet(statement, "Action", "NotAction");
        FieldSet resources = exactlyOneSet(statement, "Resource", "NotResource");
        Map<String, Map<String, List<String>>> conditions = canonicalConditions(statement.get("Condition"));
        return new StatementSemantic(sid, effect, actions.name(), actions.values(),
                resources.name(), resources.values(), conditions);
    }

    private static FieldSet exactlyOneSet(JsonNode statement, String positive, String negative) {
        if (statement.has(positive) == statement.has(negative)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        String name = statement.has(positive) ? positive : negative;
        return new FieldSet(name, canonicalStringSet(statement.path(name)));
    }

    private static List<String> canonicalStringSet(JsonNode value) {
        TreeSet<String> values = new TreeSet<>();
        if (value.isTextual()) {
            addSetValue(values, value.textValue());
        } else if (value.isArray() && !value.isEmpty() && value.size() <= 128) {
            value.forEach(item -> {
                if (!item.isTextual()) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
                addSetValue(values, item.textValue());
            });
        } else {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return List.copyOf(values);
    }

    private static void addSetValue(Set<String> values, String value) {
        if (value == null || value.isBlank() || value.length() > 2_048
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)
                || !values.add(value)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static Map<String, Map<String, List<String>>> canonicalConditions(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()
                || (value.isObject() && value.isEmpty())) {
            return Map.of();
        }
        if (!value.isObject() || value.size() > 16) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        Map<String, Map<String, List<String>>> operators = new TreeMap<>();
        value.fields().forEachRemaining(operator -> {
            if (!"StringLike".equals(operator.getKey())
                    || !operator.getValue().isObject() || operator.getValue().isEmpty()
                    || operator.getValue().size() > 16) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
            Map<String, List<String>> keys = new TreeMap<>();
            operator.getValue().fields().forEachRemaining(condition -> {
                if (!"s3:prefix".equals(condition.getKey())
                        || keys.put(condition.getKey(), canonicalStringSet(condition.getValue())) != null) {
                    throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
                }
            });
            if (operators.put(operator.getKey(), Map.copyOf(keys)) != null) {
                throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
            }
        });
        return Map.copyOf(operators);
    }

    private static void requireAllowedFields(JsonNode node, Set<String> allowed, Set<String> required) {
        Set<String> actual = fields(node);
        if (!node.isObject() || !actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
    }

    private static String requiredText(JsonNode node, String field, boolean allowEmpty) {
        String value = node.path(field).textValue();
        if (value == null || (!allowEmpty && value.isBlank()) || value.length() > 2_048
                || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
            throw new DemoException(DemoError.EXTERNAL_STATE_MISMATCH);
        }
        return value;
    }

    private record PolicySemantic(String id, String version, List<StatementSemantic> statements) {
    }

    private record StatementSemantic(
            String sid,
            String effect,
            String actionField,
            List<String> actions,
            String resourceField,
            List<String> resources,
            Map<String, Map<String, List<String>>> conditions) {
    }

    private record FieldSet(String name, List<String> values) {
    }

    private record Response(int status, byte[] body) {
        private void clear() {
            Arrays.fill(body, (byte) 0);
        }
    }
}
