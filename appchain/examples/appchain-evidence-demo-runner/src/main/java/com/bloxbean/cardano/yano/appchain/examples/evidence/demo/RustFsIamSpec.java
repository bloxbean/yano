package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict secret-free role and policy input for the RustFS one-shot bootstrap. */
record RustFsIamSpec(BootstrapRole bootstrap, Role runner, Role executor,
                     Map<String, String> policies) {
    private static final int MAX_BYTES = 65_536;
    private static final List<String> ROLE_NAMES = List.of("bootstrap", "runner", "executor");
    private static final List<String> POLICY_NAMES = List.of(
            "YanoS3RunnerV1", "YanoS3ExecutorV1");
    private static final String EXPECTED_POLICY_SET_SHA256 =
            "34cdc8e429780186ae6de2e6881d82df9cc27b5a9ed94e13dc15816580f2cb27";

    RustFsIamSpec {
        policies = Map.copyOf(policies);
    }

    static RustFsIamSpec load(Path path) {
        try {
            JsonNode root = StrictJson.parse(BoundedFiles.read(path, MAX_BYTES, true, true));
            requireFields(root, Set.of("schemaVersion", "provider", "roles", "policies"));
            if (root.path("schemaVersion").asInt(-1) != 1
                    || !"rustfs".equals(root.path("provider").textValue())
                    || !root.path("roles").isArray() || root.path("roles").size() != 3
                    || !root.path("policies").isArray() || root.path("policies").size() != 2) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }

            JsonNode bootstrapValue = root.path("roles").get(0);
            requireFields(bootstrapValue, Set.of("name", "principalType", "accessKey"));
            BootstrapRole bootstrap = new BootstrapRole(text(bootstrapValue, "name"),
                    text(bootstrapValue, "principalType"),
                    text(bootstrapValue, "accessKey"));
            if (!ROLE_NAMES.get(0).equals(bootstrap.name())
                    || !"built-in-root".equals(bootstrap.principalType())) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }

            List<Role> roles = new ArrayList<>();
            for (int index = 1; index < 3; index++) {
                JsonNode value = root.path("roles").get(index);
                requireFields(value, Set.of("name", "principalType", "accessKey", "policyName"));
                Role role = new Role(text(value, "name"), text(value, "principalType"),
                        text(value, "accessKey"), text(value, "policyName"));
                if (!ROLE_NAMES.get(index).equals(role.name())
                        || !"managed-user".equals(role.principalType())
                        || !POLICY_NAMES.get(index - 1).equals(role.policyName())) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
                roles.add(role);
            }
            if (Set.of(bootstrap.accessKey(), roles.get(0).accessKey(),
                    roles.get(1).accessKey()).size() != 3) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }

            Map<String, String> policies = new HashMap<>();
            MessageDigest policyDigest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < 2; index++) {
                JsonNode value = root.path("policies").get(index);
                requireFields(value, Set.of("name", "content"));
                String name = text(value, "name");
                String content = text(value, "content", 32_768);
                if (!POLICY_NAMES.get(index).equals(name) || content.length() > 32_768) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
                JsonNode policy = StrictJson.parse(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                requireFields(policy, Set.of("Version", "Statement"));
                if (!"2012-10-17".equals(policy.path("Version").textValue())
                        || !policy.path("Statement").isArray() || policy.path("Statement").isEmpty()
                        || policies.put(name, content) != null) {
                    throw new DemoException(DemoError.INVALID_CONFIG);
                }
                policyDigest.update(name.getBytes(StandardCharsets.UTF_8));
                policyDigest.update((byte) 0);
                policyDigest.update(content.getBytes(StandardCharsets.UTF_8));
                policyDigest.update((byte) 0);
            }
            if (!EXPECTED_POLICY_SET_SHA256.equals(
                    HexFormat.of().formatHex(policyDigest.digest()))) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
            return new RustFsIamSpec(bootstrap, roles.get(0), roles.get(1), policies);
        } catch (DemoException failure) {
            throw failure;
        } catch (BoundedFiles.InsecureFileException failure) {
            throw new DemoException(DemoError.INSECURE_SECRET_FILE);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException failure) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, 1_024);
    }

    private static String text(JsonNode node, String field, int maximumLength) {
        String value = node.path(field).textValue();
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        return value;
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (!node.isObject()) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new DemoException(DemoError.INVALID_CONFIG);
        }
    }

    record BootstrapRole(String name, String principalType, String accessKey) {
        BootstrapRole {
            if (!accessKey.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
        }
    }

    record Role(String name, String principalType, String accessKey, String policyName) {
        Role {
            if (!accessKey.matches("[A-Za-z0-9][A-Za-z0-9_-]{2,63}")) {
                throw new DemoException(DemoError.INVALID_CONFIG);
            }
        }
    }
}
