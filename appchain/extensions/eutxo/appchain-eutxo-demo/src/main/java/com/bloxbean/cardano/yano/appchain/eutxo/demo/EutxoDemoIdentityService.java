package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

/** Generates disposable member seeds without placing secrets in the manifest. */
public final class EutxoDemoIdentityService {
    private static final SecureRandom RANDOM = new SecureRandom();

    public List<String> generateMembers(Path secretDirectory, int count) throws IOException {
        if (count < 1 || count > 32) {
            throw new IllegalArgumentException("members must be between 1 and 32");
        }
        ownerDirectory(secretDirectory);
        List<String> publicKeys = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            byte[] seed = new byte[32];
            RANDOM.nextBytes(seed);
            byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
            Path target = secretDirectory.resolve("node" + index + ".env");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("member secret already exists");
            }
            Files.writeString(target,
                    "YANO_APPCHAIN_SIGNING_KEY=" + HexFormat.of().formatHex(seed) + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ownerFile(target);
            publicKeys.add(HexFormat.of().formatHex(publicKey));
            java.util.Arrays.fill(seed, (byte) 0);
        }
        return List.copyOf(publicKeys);
    }

    static void ownerDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException("secret directory cannot be a symbolic link");
        }
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems use their native ACL defaults.
        }
    }

    static void ownerFile(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX filesystems use their native ACL defaults.
        }
    }
}
