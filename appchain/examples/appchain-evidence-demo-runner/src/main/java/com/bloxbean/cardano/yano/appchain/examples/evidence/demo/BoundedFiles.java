package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;

/** Bounded, no-symlink reads for small launcher-owned configuration inputs. */
final class BoundedFiles {
    private static final Set<PosixFilePermission> SHARED_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private BoundedFiles() {
    }

    static byte[] read(Path path, int maximumBytes, boolean requireContent,
                       boolean rejectSharedPermissions) throws IOException {
        return read(path, maximumBytes, requireContent, rejectSharedPermissions, () -> { });
    }

    static String readUtf8(Path path, int maximumBytes, boolean requireContent,
                           boolean rejectSharedPermissions) throws IOException {
        byte[] content = read(path, maximumBytes, requireContent, rejectSharedPermissions);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
    }

    /** Package-private deterministic race hook used only by the file-boundary tests. */
    static byte[] read(Path path, int maximumBytes, boolean requireContent,
                       boolean rejectSharedPermissions,
                       Runnable afterInitialValidation) throws IOException {
        if (path == null || maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
            throw new InvalidFileException();
        }
        Objects.requireNonNull(afterInitialValidation, "afterInitialValidation");
        BasicFileAttributes before = attributes(path);
        validateShape(before, maximumBytes, requireContent);
        if (rejectSharedPermissions) {
            rejectSharedPermissions(path);
        }
        afterInitialValidation.run();

        byte[] content;
        OpenOption[] options = {StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS};
        try (InputStream input = Files.newInputStream(path, options)) {
            content = input.readNBytes(maximumBytes + 1);
        }
        if (content.length > maximumBytes
                || (requireContent && content.length == 0)
                || content.length != before.size()) {
            throw new InvalidFileException();
        }

        BasicFileAttributes after = attributes(path);
        validateShape(after, maximumBytes, requireContent);
        if (!sameFileSnapshot(before, after)) {
            throw new InvalidFileException();
        }
        if (rejectSharedPermissions) {
            rejectSharedPermissions(path);
        }
        return content;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static void validateShape(BasicFileAttributes attributes,
                                      int maximumBytes,
                                      boolean requireContent) throws InvalidFileException {
        if (!attributes.isRegularFile() || attributes.size() > maximumBytes
                || (requireContent && attributes.size() < 1)) {
            throw new InvalidFileException();
        }
    }

    private static boolean sameFileSnapshot(BasicFileAttributes before,
                                            BasicFileAttributes after) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(beforeKey, afterKey);
    }

    private static void rejectSharedPermissions(Path path) throws IOException {
        try {
            if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    .stream().anyMatch(SHARED_PERMISSIONS::contains)) {
                throw new InsecureFileException();
            }
        } catch (UnsupportedOperationException ignored) {
            // No portable permission model exists on this filesystem. The
            // regular-file, no-symlink, stable-snapshot, and byte bounds remain.
        }
    }

    static final class InvalidFileException extends IOException {
    }

    static final class InsecureFileException extends IOException {
    }
}
