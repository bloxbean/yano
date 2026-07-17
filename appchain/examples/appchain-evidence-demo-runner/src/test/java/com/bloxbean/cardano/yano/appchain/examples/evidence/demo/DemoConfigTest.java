package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoConfigTest {
    @TempDir
    Path temporary;

    @Test
    void loadsCanonicalOriginsPathsAndSecretFilesWithoutPrintingSecrets() throws IOException {
        DemoConfig config = DemoConfig.load(DemoTestFiles.config(temporary));

        assertThat(config.chainId()).isEqualTo("evidence-chain");
        assertThat(config.stateMachine()).isEqualTo("evidence-registry");
        assertThat(config.yanoUrls()).hasSize(3);
        assertThat(config.yanoApiKey()).isNull();
        assertThat(config.s3().endpoint().toString()).isEqualTo("http://s3.example:9000");
        assertThat(config.ipfs().apiUrl().toString()).isEqualTo("http://ipfs.example:5001");
        assertThat(config.sampleFile()).isAbsolute();
        assertThat(config.toString())
                .doesNotContain("super-api-key", "s3-demo-access", "s3-demo-secret")
                .contains("<redacted>");
    }

    @Test
    void selectsOnlyTheExplicitStockCompositeMachine() throws IOException {
        Path configFile = DemoTestFiles.config(temporary);
        Files.writeString(configFile, DemoTestFiles.properties()
                + "demo.state-machine=composite\n"
                + "demo.composite-profile-digest=" + "ab".repeat(32) + "\n");

        assertThat(DemoConfig.load(configFile).stateMachine()).isEqualTo("composite");
        assertThat(DemoConfig.load(configFile).expectedCompositeProfileDigest())
                .isEqualTo(java.util.HexFormat.of().parseHex("ab".repeat(32)));

        Files.writeString(configFile, DemoTestFiles.properties()
                + "demo.state-machine=custom\n");
        assertThatThrownBy(() -> DemoConfig.load(configFile))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);

        Files.writeString(configFile, DemoTestFiles.properties()
                + "demo.state-machine=composite\n");
        assertThatThrownBy(() -> DemoConfig.load(configFile))
                .isInstanceOf(DemoException.class);
    }

    @Test
    void optionallyLoadsOnlyASecretFileBackedScopedYanoKey() throws IOException {
        Path configFile = DemoTestFiles.config(temporary);
        DemoTestFiles.secret(temporary.resolve("secrets/api"), "scoped-read-submit-key");
        Files.writeString(configFile, DemoTestFiles.properties()
                + "demo.yano.api-key-file=secrets/api\n");

        DemoConfig config = DemoConfig.load(configFile);

        assertThat(config.yanoApiKey()).isNotNull();
        assertThat(config.yanoApiKey().toString()).isEqualTo("<redacted>");
        assertThat(config.toString()).doesNotContain("scoped-read-submit-key");
    }

    @Test
    void kafkaAuditSettingsDoNotReadUnrelatedConnectorCredentials() throws Exception {
        Path config = DemoTestFiles.config(temporary);
        Files.delete(temporary.resolve("secrets/access"));
        Files.delete(temporary.resolve("secrets/secret"));

        DemoConfig.KafkaSettings kafka = DemoConfig.loadKafkaSettings(config);

        assertThat(kafka.bootstrapServers()).isEqualTo("kafka:9092");
        assertThat(kafka.physicalTopic()).isEqualTo("evidence.available.v1");
    }

    @Test
    void rejectsOversizedAndMalformedUtf8Configuration() throws Exception {
        Path config = temporary.resolve("runner.properties");
        Files.write(config, new byte[65_537]);
        assertThatThrownBy(() -> DemoConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);

        Files.write(config, new byte[]{(byte) 0xc3, (byte) 0x28});
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(config, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
        assertThatThrownBy(() -> DemoConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);
    }

    @Test
    void rejectsSharedRunnerConfigurationBecauseItSelectsCredentialDestinations()
            throws Exception {
        Path config = DemoTestFiles.config(temporary);
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(config, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));
            assertThatThrownBy(() -> DemoConfig.load(config))
                    .isInstanceOf(DemoException.class)
                    .extracting(failure -> ((DemoException) failure).error())
                    .isEqualTo(DemoError.INVALID_CONFIG);
        }
    }

    @Test
    void rejectsUnknownDuplicateMissingAndInlineSecretKeys() throws IOException {
        Path config = DemoTestFiles.config(temporary);
        assertCode(config, DemoTestFiles.properties() + "unknown.key=x\n",
                DemoError.UNKNOWN_CONFIG_KEY);
        assertCode(config, DemoTestFiles.properties() + "demo.chain-id=again\n",
                DemoError.INVALID_CONFIG);
        assertCode(config, DemoTestFiles.properties().replace(
                        "demo.chain-id=evidence-chain\n", ""),
                DemoError.MISSING_CONFIG_KEY);
        assertCode(config, DemoTestFiles.properties() + "demo.yano.api-key=inline\n",
                DemoError.UNKNOWN_CONFIG_KEY);
    }

    @Test
    void rejectsNonCanonicalEndpointPathsWithoutResolution() throws IOException {
        Path config = DemoTestFiles.config(temporary);
        assertCode(config, DemoTestFiles.properties().replace(
                        "s3.endpoint=http://s3.example:9000",
                        "s3.endpoint=http://s3.example:9000/a"), DemoError.INVALID_CONFIG);
        assertCode(config, DemoTestFiles.properties().replace(
                        "ipfs.api-url=http://ipfs.example:5001",
                        "ipfs.api-url=http://ipfs.example:5001/%2F"), DemoError.INVALID_CONFIG);
        assertCode(config, DemoTestFiles.properties().replace(
                        "demo.yano.urls=http://127.0.0.1:7070/api/v1,"
                                + "http://127.0.0.1:7071/api/v1,"
                                + "http://127.0.0.1:7072/api/v1",
                        "demo.yano.urls=http://127.0.0.1:7070/api/../v1"),
                DemoError.INVALID_CONFIG);
    }

    @Test
    void rejectsAnythingButThreeDistinctCanonicalYanoEndpoints() throws IOException {
        Path config = DemoTestFiles.config(temporary);
        String endpoints = "demo.yano.urls=http://127.0.0.1:7070/api/v1,"
                + "http://127.0.0.1:7071/api/v1,http://127.0.0.1:7072/api/v1";
        assertCode(config, DemoTestFiles.properties().replace(endpoints,
                        "demo.yano.urls=http://node-a/api/v1"),
                DemoError.INVALID_CONFIG);
        assertCode(config, DemoTestFiles.properties().replace(endpoints,
                        "demo.yano.urls=http://node-a/api/v1,http://node-a:80/api/v1,"
                                + "http://node-c/api/v1"),
                DemoError.INVALID_CONFIG);
    }

    @Test
    void rejectsConnectorAliasesAndObjectPrefixesOutsideContractGrammar() throws IOException {
        Path config = DemoTestFiles.config(temporary);
        for (String alias : Set.of("Uppercase", "with.dot", "with_under",
                "a".repeat(64))) {
            assertCode(config, DemoTestFiles.properties().replace(
                            "kafka.topic-alias=evidence-ready",
                            "kafka.topic-alias=" + alias),
                    DemoError.INVALID_CONFIG);
        }
        assertCode(config, DemoTestFiles.properties().replace(
                        "s3.source-prefix=staged",
                        "s3.source-prefix=a//b"), DemoError.INVALID_CONFIG);
        assertCode(config, DemoTestFiles.properties().replace(
                        "s3.destination-prefix=verified",
                        "s3.destination-prefix=" + "a".repeat(129)),
                DemoError.INVALID_CONFIG);
    }

    @Test
    void rejectsSymlinkAndSharedSecretWithoutReflectingPathOrValue() throws IOException {
        Path config = DemoTestFiles.config(temporary);
        Path api = temporary.resolve("secrets/api");
        DemoTestFiles.secret(api, "super-api-key");
        String properties = DemoTestFiles.properties()
                + "demo.yano.api-key-file=secrets/api\n";
        Files.writeString(config, properties);
        if (Files.getFileStore(api).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(api, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ));
            assertCode(config, properties, DemoError.INSECURE_SECRET_FILE);
            DemoTestFiles.secret(api, "super-api-key");
            Files.writeString(config, properties);
        }

        Path target = temporary.resolve("secrets/target");
        DemoTestFiles.secret(target, "do-not-reflect-this");
        Files.delete(api);
        try {
            Files.createSymbolicLink(api, target);
            Throwable failure = catchFailure(config);
            assertThat(failure.getMessage())
                    .isEqualTo(DemoError.INVALID_SECRET_FILE.name())
                    .doesNotContain("target", "do-not-reflect-this");
        } catch (UnsupportedOperationException ignored) {
            // Symlinks may be unavailable on some test hosts.
        }
    }

    private static void assertCode(Path config, String properties, DemoError expected)
            throws IOException {
        Files.writeString(config, properties);
        assertThatThrownBy(() -> DemoConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(expected);
    }

    private static Throwable catchFailure(Path config) {
        try {
            DemoConfig.load(config);
            throw new AssertionError("expected failure");
        } catch (DemoException failure) {
            return failure;
        }
    }
}
