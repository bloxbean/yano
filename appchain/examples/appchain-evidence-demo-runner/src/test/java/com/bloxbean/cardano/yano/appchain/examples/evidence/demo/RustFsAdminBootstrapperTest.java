package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RustFsAdminBootstrapperTest {
    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Sid":"ReadEvidence","Effect":"Allow",
             "Action":["s3:GetObjectVersion","s3:GetObject"],
             "Resource":"arn:aws:s3:::evidence/*",
             "Condition":{"StringLike":{"s3:prefix":["incoming/v1","incoming/v1/*"]}}}]}
            """.trim();

    @Test
    void acceptsTheExactPolicyStoredByRustFs() {
        byte[] response = bytes("""
                {"policy_name":"YanoS3RunnerV1","policy":{"ID":"","Version":"2012-10-17",
                 "Statement":[{"Sid":"ReadEvidence","Effect":"Allow","Condition":
                 {"StringLike":{"s3:prefix":["incoming/v1/*","incoming/v1"]}},
                 "Action":["s3:GetObject","s3:GetObjectVersion"],
                 "Resource":["arn:aws:s3:::evidence/*"]}]},
                 "create_date":"2026-07-16 01:02:03.000000000 +00:00:00",
                 "update_date":"2026-07-16 01:02:04.123000000 +00:00:00"}
                """);

        assertThatCode(() -> RustFsAdminBootstrapper.validatePolicyResponse(
                response, "YanoS3RunnerV1", POLICY)).doesNotThrowAnyException();
    }

    @Test
    void rejectsPolicyIdentityContentShapeAndTimestampDrift() {
        assertMismatch(() -> RustFsAdminBootstrapper.validatePolicyResponse(
                bytes("{\"policy_name\":\"other\",\"policy\":" + POLICY + "}"),
                "YanoS3RunnerV1", POLICY));
        assertMismatch(() -> RustFsAdminBootstrapper.validatePolicyResponse(
                bytes("{\"policy_name\":\"YanoS3RunnerV1\",\"policy\":{}" +
                        ",\"update_date\":\"2026-07-16T01:02:04Z\"}"),
                "YanoS3RunnerV1", POLICY));
        assertMismatch(() -> RustFsAdminBootstrapper.validatePolicyResponse(
                bytes("{\"policy_name\":\"YanoS3RunnerV1\",\"policy\":" + POLICY +
                        ",\"unexpected\":true}"), "YanoS3RunnerV1", POLICY));
        assertMismatch(() -> RustFsAdminBootstrapper.validatePolicyResponse(
                bytes("{\"policy_name\":\"YanoS3RunnerV1\",\"policy\":" +
                        POLICY.replace("s3:GetObjectVersion", "s3:DeleteObject") + "}"),
                "YanoS3RunnerV1", POLICY));
        assertMismatch(() -> RustFsAdminBootstrapper.validatePolicyResponse(
                bytes("{\"policy_name\":\"YanoS3RunnerV1\",\"policy\":" + POLICY +
                        ",\"create_date\":\"bad\\nvalue\"}"), "YanoS3RunnerV1", POLICY));
    }

    @Test
    void acceptsOnlyTheExactEnabledSinglePolicyUserResponse() {
        byte[] valid = bytes("""
                {"policyName":"YanoS3RunnerV1","status":"enabled",
                 "updatedAt":"2026-07-16T01:02:04Z"}
                """);
        assertThatCode(() -> RustFsAdminBootstrapper.validateUserResponse(
                valid, "YanoS3RunnerV1")).doesNotThrowAnyException();

        assertMismatch(() -> RustFsAdminBootstrapper.validateUserResponse(bytes("""
                {"policyName":"YanoS3RunnerV1","status":"enabled",
                 "updatedAt":"2026-07-16T01:02:04Z","secretKey":"must-not-be-returned"}
                """), "YanoS3RunnerV1"));
        assertMismatch(() -> RustFsAdminBootstrapper.validateUserResponse(bytes("""
                {"policyName":"YanoS3RunnerV1","status":"disabled",
                 "updatedAt":"2026-07-16T01:02:04Z"}
                """), "YanoS3RunnerV1"));
        assertMismatch(() -> RustFsAdminBootstrapper.validateUserResponse(bytes("""
                {"policyName":"YanoS3RunnerV1","status":"enabled"}
                """), "YanoS3RunnerV1"));
    }

    @Test
    void acceptsOnlyTheThreeExpectedPrincipalsWithoutServiceOrStsKeys() {
        RustFsIamSpec.Role runner = new RustFsIamSpec.Role(
                "runner", "managed-user", "runner-key", "YanoS3RunnerV1");
        RustFsIamSpec.Role executor = new RustFsIamSpec.Role(
                "executor", "managed-user", "executor-key", "YanoS3ExecutorV1");
        byte[] users = bytes("""
                {"runner-key":{"policyName":"YanoS3RunnerV1","status":"enabled",
                 "updatedAt":"2026-07-16T01:02:04Z"},
                 "executor-key":{"policyName":"YanoS3ExecutorV1","status":"enabled",
                 "updatedAt":"2026-07-16T01:02:05Z"}}
                """);
        assertThatCode(() -> RustFsAdminBootstrapper.validateUserInventory(
                users, runner, executor)).doesNotThrowAnyException();
        Set<String> principals = Set.of("root-key", "runner-key", "executor-key");
        assertThatCode(() -> RustFsAdminBootstrapper.validateAccessKeyInventory(bytes("""
                {"root-key":{"serviceAccounts":[],"stsKeys":[]},
                 "runner-key":{"serviceAccounts":[],"stsKeys":[]},
                 "executor-key":{"serviceAccounts":[],"stsKeys":[]}}
                """), principals)).doesNotThrowAnyException();

        assertMismatch(() -> RustFsAdminBootstrapper.validateUserInventory(bytes("""
                {"runner-key":{"policyName":"YanoS3RunnerV1","status":"enabled",
                 "updatedAt":"2026-07-16T01:02:04Z"},
                 "executor-key":{"policyName":"YanoS3ExecutorV1","status":"enabled",
                 "updatedAt":"2026-07-16T01:02:05Z"},
                 "unexpected":{"policyName":"YanoS3RunnerV1","status":"enabled",
                 "updatedAt":"2026-07-16T01:02:06Z"}}
                """), runner, executor));
        assertMismatch(() -> RustFsAdminBootstrapper.validateAccessKeyInventory(bytes("""
                {"root-key":{"serviceAccounts":[],"stsKeys":[]},
                 "runner-key":{"serviceAccounts":[],"stsKeys":[{"accessKey":"unexpected"}]},
                 "executor-key":{"serviceAccounts":[],"stsKeys":[]}}
                """), principals));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertMismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.EXTERNAL_STATE_MISMATCH);
    }
}
