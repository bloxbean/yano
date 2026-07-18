package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceDemoMainTest {
    @TempDir
    Path temporary;

    @Test
    void validateConfigParsesWithoutContactingExternalServices() throws Exception {
        Path config = DemoTestFiles.config(temporary);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int result = EvidenceDemoMain.run(
                new String[]{"validate-config", "--config", config.toString()},
                new PrintStream(output), new PrintStream(error));

        assertThat(result).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("PASS command=validate-config\n");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void cliPrintsOnlyStableFailureCodeForInvalidOrSecretFileFailures() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int invalid = EvidenceDemoMain.run(new String[]{"run", "inline-secret"},
                new PrintStream(output), new PrintStream(error));
        assertThat(invalid).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_ARGUMENT\n")
                .doesNotContain("inline-secret");

        error.reset();
        int invalidLoad = EvidenceDemoMain.run(new String[]{
                        "load", "--config", "inline-secret",
                        "--sample-file", "sample.json", "--id-prefix", "load-case"},
                new PrintStream(output), new PrintStream(error));
        assertThat(invalidLoad).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_ARGUMENT\n")
                .doesNotContain("inline-secret", "sample.json");

        error.reset();
        int unsafeConcurrency = EvidenceDemoMain.run(new String[]{
                        "load", "--config", "inline-secret", "--sample-file", "sample.json",
                        "--id-prefix", "load-case", "--count", "2", "--concurrency", "3"},
                new PrintStream(output), new PrintStream(error));
        assertThat(unsafeConcurrency).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_ARGUMENT\n");

        error.reset();
        int invalidPipeline = EvidenceDemoMain.run(new String[]{
                        "load", "--config", "inline-secret", "--sample-file", "sample.json",
                        "--id-prefix", "load-case", "--count", "4", "--concurrency", "2",
                        "--load-mode", "pipeline", "--max-in-flight", "1"},
                new PrintStream(output), new PrintStream(error));
        assertThat(invalidPipeline).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_ARGUMENT\n");

        error.reset();
        int invalidMode = EvidenceDemoMain.run(new String[]{
                        "load", "--config", "inline-secret", "--sample-file", "sample.json",
                        "--id-prefix", "load-case", "--count", "4",
                        "--load-mode", "unbounded"},
                new PrintStream(output), new PrintStream(error));
        assertThat(invalidMode).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_ARGUMENT\n");

        Path config = DemoTestFiles.config(temporary);
        DemoTestFiles.secret(temporary.resolve("secrets/api"), "scoped-key");
        Files.writeString(config, DemoTestFiles.properties()
                + "demo.yano.api-key-file=secrets/api\n");
        Files.delete(temporary.resolve("secrets/api"));
        error.reset();
        int unavailable = EvidenceDemoMain.run(
                new String[]{"run", "--config", config.toString()},
                new PrintStream(output), new PrintStream(error));
        assertThat(unavailable).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_SECRET_FILE\n")
                .doesNotContain(config.toString(), "api");

        error.reset();
        int knownConnectorCommand = EvidenceDemoMain.run(
                new String[]{"init-connectors", "--config", temporary.resolve("missing").toString()},
                new PrintStream(output), new PrintStream(error));
        assertThat(knownConnectorCommand).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_CONFIG\n");

        error.reset();
        int invalidAudit = EvidenceDemoMain.run(new String[]{
                        "audit-kafka", "--config", config.toString(),
                        "--expected-records", "17",
                        "--expected-effect-id", "ab".repeat(32)},
                new PrintStream(output), new PrintStream(error));
        assertThat(invalidAudit).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_ARGUMENT\n")
                .doesNotContain(config.toString(), "scoped-key");

        error.reset();
        int knownBootstrapCommand = EvidenceDemoMain.run(
                new String[]{"bootstrap-s3", "--config", temporary.resolve("missing").toString()},
                new PrintStream(output), new PrintStream(error));
        assertThat(knownBootstrapCommand).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_CONFIG\n");

        error.reset();
        EvidenceDemoMain.run(new String[]{"unknown", "--config", "secret-path"},
                new PrintStream(output), new PrintStream(error));
        assertThat(error.toString(StandardCharsets.UTF_8))
                .isEqualTo("FAIL code=INVALID_ARGUMENT\n")
                .doesNotContain("secret-path");
    }
}
