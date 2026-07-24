package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Public provenance supplied alongside a Groth16 proving-key directory.
 *
 * <p>A manifest proves byte identity, not that a ceremony was independent or
 * honestly conducted. Production readiness separately requires accountable,
 * external transcript evidence.</p>
 */
public record EutxoCeremonyManifest(
        String ceremonyId,
        Kind kind,
        String method,
        int participantCount,
        String transcriptDigest,
        String profileDigest,
        String circuitId,
        String verificationKeyDigest,
        Map<String, String> fileDigests
) {
    private static final Pattern DIGEST =
            Pattern.compile("[0-9a-f]{64}");

    public EutxoCeremonyManifest {
        if (ceremonyId == null || ceremonyId.isBlank()
                || ceremonyId.length() > 128) {
            throw new IllegalArgumentException("invalid ceremony id");
        }
        Objects.requireNonNull(kind, "kind");
        if (method == null || method.isBlank() || method.length() > 128
                || participantCount < 1) {
            throw new IllegalArgumentException("invalid ceremony description");
        }
        if (kind == Kind.PRODUCTION
                && (participantCount < 2
                || !"multi-party-contribution".equals(method))) {
            throw new IllegalArgumentException(
                    "production ceremony must be multi-party");
        }
        requireDigest(transcriptDigest, "transcript");
        requireDigest(profileDigest, "profile");
        requireDigest(verificationKeyDigest, "verification key");
        if (circuitId == null || circuitId.isBlank()
                || circuitId.length() > 128) {
            throw new IllegalArgumentException("invalid circuit id");
        }
        Objects.requireNonNull(fileDigests, "fileDigests");
        if (fileDigests.isEmpty()) {
            throw new IllegalArgumentException(
                    "ceremony file inventory is empty");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        fileDigests.forEach((name, digest) -> {
            if (name == null || name.isBlank() || name.startsWith("/")
                    || name.contains("..") || name.contains("\\")) {
                throw new IllegalArgumentException(
                        "invalid ceremony file name");
            }
            requireDigest(digest, "ceremony file");
            sorted.put(name, digest);
        });
        fileDigests = Map.copyOf(sorted);
    }

    public static EutxoCeremonyManifest development(
            String ceremonyId,
            Path keyDirectory,
            EutxoZkVerificationKey verificationKey
    ) {
        Objects.requireNonNull(verificationKey, "verificationKey");
        Map<String, String> files = inventory(keyDirectory);
        MessageDigest transcript = sha256();
        new TreeMap<>(files).forEach((name, digest) -> {
            transcript.update(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            transcript.update(HexFormat.of().parseHex(digest));
        });
        return new EutxoCeremonyManifest(
                ceremonyId,
                Kind.DEVELOPMENT,
                "zeroj-single-development-setup",
                1,
                HexFormat.of().formatHex(transcript.digest()),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.circuitId(),
                verificationKey.digestHex(),
                files);
    }

    static Map<String, String> inventory(Path keyDirectory) {
        Objects.requireNonNull(keyDirectory, "keyDirectory");
        Path root = keyDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException(
                    "ceremony key directory does not exist");
        }
        TreeMap<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException(
                            "ceremony bundle must not contain symbolic links");
                }
                if (!Files.isRegularFile(path)) {
                    return;
                }
                String relative = root.relativize(path.toAbsolutePath()
                        .normalize()).toString().replace('\\', '/');
                files.put(relative, digest(path));
            });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "cannot inventory ceremony bundle", exception);
        }
        return Map.copyOf(files);
    }

    private static String digest(Path path) {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "cannot hash ceremony artifact " + path, exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireDigest(String value, String label) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label + " digest");
        }
    }

    public enum Kind {
        DEVELOPMENT,
        PRODUCTION
    }
}
