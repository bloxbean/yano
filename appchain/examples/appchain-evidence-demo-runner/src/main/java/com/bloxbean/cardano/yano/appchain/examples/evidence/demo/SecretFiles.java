package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Reads small UTF-8 secrets from regular, non-shared files. */
final class SecretFiles {
    private static final long MAX_SECRET_BYTES = 4_096;
    private static final Set<PosixFilePermission> SHARED_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);

    private SecretFiles() {
    }

    static SecretValue read(Path path) {
        try {
            if (path == null || Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new DemoException(DemoError.INVALID_SECRET_FILE);
            }
            long size = Files.size(path);
            if (size <= 0 || size > MAX_SECRET_BYTES) {
                throw new DemoException(DemoError.INVALID_SECRET_FILE);
            }
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                        path, LinkOption.NOFOLLOW_LINKS);
                if (permissions.stream().anyMatch(SHARED_PERMISSIONS::contains)) {
                    throw new DemoException(DemoError.INSECURE_SECRET_FILE);
                }
            }
            byte[] bytes = Files.readAllBytes(path);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            String value = stripOneLineEnding(decoded);
            if (value.isEmpty() || value.length() > 1_024
                    || value.chars().anyMatch(character -> character < 0x21 || character == 0x7f)) {
                throw new DemoException(DemoError.INVALID_SECRET_FILE);
            }
            return new SecretValue(value);
        } catch (DemoException failure) {
            throw failure;
        } catch (CharacterCodingException failure) {
            throw new DemoException(DemoError.INVALID_SECRET_FILE);
        } catch (IOException failure) {
            throw new DemoException(DemoError.SECRET_FILE_UNAVAILABLE);
        }
    }

    private static String stripOneLineEnding(String value) {
        String stripped = value;
        if (stripped.endsWith("\n")) {
            stripped = stripped.substring(0, stripped.length() - 1);
            if (stripped.endsWith("\r")) {
                stripped = stripped.substring(0, stripped.length() - 1);
            }
        }
        return stripped;
    }
}
