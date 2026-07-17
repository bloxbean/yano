package com.bloxbean.cardano.yano.appchain.objectstore.s3.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

/** Reads bounded integration credentials without placing their values in JVM arguments. */
public final class IntegrationSecretFiles {
    private static final int MAX_SECRET_BYTES = 256;
    private static final Set<PosixFilePermission> FORBIDDEN_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private IntegrationSecretFiles() {
    }

    /**
     * Reads one printable, owner-only secret from the path named by a system property.
     *
     * @param property system property containing the secret-file path
     * @return the secret value, without one optional trailing LF or CRLF
     */
    public static String read(String property) {
        String configured = System.getProperty(property, "").trim();
        if (configured.isEmpty()) {
            throw new IllegalStateException("Missing required integration secret file: " + property);
        }

        Path path = Path.of(configured).toAbsolutePath().normalize();
        try {
            String value = readStable(path, property);
            if (value.endsWith("\r\n")) {
                value = value.substring(0, value.length() - 2);
            } else if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            if (value.isEmpty() || value.length() > MAX_SECRET_BYTES
                    || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
                throw invalid(property);
            }
            return value;
        } catch (IOException failure) {
            throw invalid(property);
        }
    }

    private static String readStable(Path path, String property) throws IOException {
        BasicFileAttributes before = attributes(path);
        validate(before, property);
        rejectSharedPermissions(path, property);
        byte[] content;
        try (InputStream input = Files.newInputStream(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            content = input.readNBytes(MAX_SECRET_BYTES + 1);
        }
        if (content.length > MAX_SECRET_BYTES || content.length != before.size()) {
            throw invalid(property);
        }
        BasicFileAttributes after = attributes(path);
        validate(after, property);
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw invalid(property);
        }
        rejectSharedPermissions(path, property);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void validate(BasicFileAttributes attributes, String property) {
        if (!attributes.isRegularFile() || attributes.size() < 1
                || attributes.size() > MAX_SECRET_BYTES) {
            throw invalid(property);
        }
    }

    private static void rejectSharedPermissions(Path path, String property) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(FORBIDDEN_PERMISSIONS::contains)) {
                throw invalid(property);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems still receive the no-symlink, regular-file,
            // and bounded-content checks above.
        }
    }

    private static IllegalStateException invalid(String property) {
        return new IllegalStateException("Invalid integration secret file: " + property);
    }
}
