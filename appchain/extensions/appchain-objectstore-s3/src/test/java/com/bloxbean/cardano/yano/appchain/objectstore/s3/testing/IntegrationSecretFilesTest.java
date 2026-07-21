package com.bloxbean.cardano.yano.appchain.objectstore.s3.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationSecretFilesTest {
    private static final String PROPERTY = "yano.s3.integration.test-secret-file";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void readsOwnerOnlyRegularFileAndStripsOneTrailingLineEnding() throws IOException {
        Path secret = secretFile("safe", "test-secret\n");
        System.setProperty(PROPERTY, secret.toString());

        assertThat(IntegrationSecretFiles.read(PROPERTY)).isEqualTo("test-secret");
    }

    @Test
    void rejectsSymlinkAndNonPrintableContent() throws IOException {
        Path target = secretFile("target", "credential-canary\n");
        Path link = temporaryDirectory.resolve("link");
        Files.createSymbolicLink(link, target);
        System.setProperty(PROPERTY, link.toString());
        assertThatThrownBy(() -> IntegrationSecretFiles.read(PROPERTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageNotContaining("credential-canary");

        Path nonPrintable = secretFile("non-printable", "line-one\nline-two\n");
        System.setProperty(PROPERTY, nonPrintable.toString());
        assertThatThrownBy(() -> IntegrationSecretFiles.read(PROPERTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY);

        Path repeatedLineEnding = secretFile("repeated-line-ending", "credential-canary\n\n");
        System.setProperty(PROPERTY, repeatedLineEnding.toString());
        assertThatThrownBy(() -> IntegrationSecretFiles.read(PROPERTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageNotContaining("credential-canary");
    }

    @Test
    void rejectsGroupReadablePosixFile() throws IOException {
        Path secret = secretFile("shared", "test-secret\n");
        try {
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r-----"));
        } catch (UnsupportedOperationException unsupported) {
            return;
        }
        System.setProperty(PROPERTY, secret.toString());

        assertThatThrownBy(() -> IntegrationSecretFiles.read(PROPERTY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY);
    }

    private Path secretFile(String name, String value) throws IOException {
        Path path = temporaryDirectory.resolve(name);
        Files.writeString(path, value, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Content and link checks remain portable to non-POSIX filesystems.
        }
        return path;
    }
}
