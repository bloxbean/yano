package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RustFsIamSpecTest {
    @TempDir
    Path temporary;

    @Test
    void acceptsOnlyTheExactIndependentlyBoundV1PolicySet() throws Exception {
        Path specFile = validSpec();

        RustFsIamSpec spec = RustFsIamSpec.load(specFile);

        assertThat(spec.bootstrap().accessKey()).isEqualTo("yano-probe-admin-001");
        assertThat(spec.policies()).containsOnlyKeys(
                "YanoS3RunnerV1", "YanoS3ExecutorV1");
        assertThat(spec.runner().policyName()).isEqualTo("YanoS3RunnerV1");
        assertThat(spec.executor().policyName()).isEqualTo("YanoS3ExecutorV1");
    }

    @Test
    void rejectsPolicyPrivilegeAndEvenSemanticallyEquivalentTextTampering() throws Exception {
        Path specFile = validSpec();
        String valid = Files.readString(specFile);

        DemoTestFiles.secret(specFile, valid.trim().replaceFirst("s3:GetObject", "s3:*"));
        assertInvalid(specFile);

        DemoTestFiles.secret(specFile, valid.trim().replace(
                "\"content\":\"{", "\"content\":\"{ "));
        assertInvalid(specFile);

        DemoTestFiles.secret(specFile, valid.trim().replace(
                "\"name\":\"bootstrap\",\"principalType\":\"built-in-root\"",
                "\"name\":\"bootstrap\",\"policyName\":\"YanoS3RunnerV1\","
                        + "\"principalType\":\"built-in-root\""));
        assertInvalid(specFile);
    }

    @Test
    void rejectsAnOversizedSpecificationWithoutParsingIt() throws Exception {
        Path specFile = temporary.resolve("oversized-iam.json");
        DemoTestFiles.secret(specFile, "x".repeat(65_536));

        assertInvalid(specFile);
    }

    private void assertInvalid(Path specFile) {
        assertThatThrownBy(() -> RustFsIamSpec.load(specFile))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);
    }

    private Path validSpec() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/rustfs-iam-spec-v1.json")) {
            if (input == null) {
                throw new IllegalStateException("missing RustFS IAM test fixture");
            }
            bytes = input.readAllBytes();
        }
        Path path = temporary.resolve("rustfs-iam-spec.json");
        DemoTestFiles.secret(path, new String(bytes, StandardCharsets.UTF_8).trim());
        return path;
    }
}
