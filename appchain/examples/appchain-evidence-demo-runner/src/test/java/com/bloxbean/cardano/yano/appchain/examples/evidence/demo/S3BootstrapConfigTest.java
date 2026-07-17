package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3BootstrapConfigTest {
    @TempDir
    Path temporary;

    @Test
    void loadsOnlyTheFixedLocalDemoBucketsFromOwnerOnlySecretFiles() throws Exception {
        Path config = bootstrapConfig();

        S3BootstrapConfig loaded = S3BootstrapConfig.load(config);

        assertThat(loaded.endpoint().toString()).isEqualTo("http://127.0.0.1:9000");
        assertThat(loaded.sourceBucket()).isEqualTo("evidence-staging");
        assertThat(loaded.destinationBucket()).isEqualTo("evidence-archive");
        assertThat(loaded.toString()).doesNotContain("bootstrap-access", "bootstrap-secret")
                .contains("<redacted>");
    }

    @Test
    void rejectsUnknownKeysInlineSecretsAndAnyOtherBucket() throws Exception {
        Path config = bootstrapConfig();
        String valid = Files.readString(config);

        assertThatThrownBy(() -> {
            Files.writeString(config, valid + "s3.inline-secret=nope\n");
            S3BootstrapConfig.load(config);
        }).isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.UNKNOWN_CONFIG_KEY);

        assertThatThrownBy(() -> {
            Files.writeString(config, valid.replace(
                    "s3.destination-bucket=evidence-archive",
                    "s3.destination-bucket=operator-data"));
            S3BootstrapConfig.load(config);
        }).isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);
    }

    @Test
    void rejectsAnyEndpointThatCouldExfiltrateTheBootstrapRootCredential() throws Exception {
        Path config = bootstrapConfig();
        String valid = Files.readString(config);

        for (String endpoint : new String[]{
                "http://rustfs:9000", "https://127.0.0.1:9000",
                "http://8.8.8.8:9000", "http://127.0.0.1",
                "http://0177.0.0.1:9000", "http://[::1]:9000"}) {
            Files.writeString(config, valid.replace(
                    "http://127.0.0.1:9000", endpoint));
            assertThatThrownBy(() -> S3BootstrapConfig.load(config))
                    .isInstanceOf(DemoException.class)
                    .extracting(failure -> ((DemoException) failure).error())
                    .isEqualTo(DemoError.INVALID_CONFIG);
        }
    }

    @Test
    void rejectsVirtualHostAddressingThatWouldRequireDnsResolution() throws Exception {
        Path config = bootstrapConfig();
        Files.writeString(config, Files.readString(config).replace(
                "s3.path-style=true", "s3.path-style=false"));

        assertThatThrownBy(() -> S3BootstrapConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);
    }

    @Test
    void rejectsOversizedAndMalformedUtf8Configuration() throws Exception {
        Path config = bootstrapConfig();
        Files.write(config, new byte[16_385]);
        assertInvalidConfig(config);

        Files.write(config, new byte[]{(byte) 0xc3, (byte) 0x28});
        assertInvalidConfig(config);
    }

    @Test
    void rejectsSharedBootstrapConfigurationBeforeUsingRootCredentials() throws Exception {
        Path config = bootstrapConfig();
        Assumptions.assumeTrue(Files.getFileStore(config)
                .supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(config, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));

        assertInvalidConfig(config);
    }

    private static void assertInvalidConfig(Path config) {
        assertThatThrownBy(() -> S3BootstrapConfig.load(config))
                .isInstanceOf(DemoException.class)
                .extracting(failure -> ((DemoException) failure).error())
                .isEqualTo(DemoError.INVALID_CONFIG);
    }

    private Path bootstrapConfig() throws Exception {
        Path secrets = temporary.resolve("secrets");
        Files.createDirectories(secrets);
        DemoTestFiles.secret(secrets.resolve("access"), "bootstrap-access");
        DemoTestFiles.secret(secrets.resolve("secret"), "bootstrap-secret");
        DemoTestFiles.secret(secrets.resolve("runner-secret"), "runner-secret");
        DemoTestFiles.secret(secrets.resolve("executor-secret"), "executor-secret");
        DemoTestFiles.secret(secrets.resolve("iam.json"), "{}");
        Path config = temporary.resolve("s3-bootstrap.properties");
        Files.writeString(config, """
                s3.endpoint=http://127.0.0.1:9000
                s3.region=us-east-1
                s3.access-key-file=secrets/access
                s3.secret-key-file=secrets/secret
                s3.iam-provider=rustfs-v1
                s3.iam-spec-file=secrets/iam.json
                s3.runner-secret-key-file=secrets/runner-secret
                s3.executor-secret-key-file=secrets/executor-secret
                s3.source-bucket=evidence-staging
                s3.destination-bucket=evidence-archive
                s3.path-style=true
                """);
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(config, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        }
        return config;
    }
}
