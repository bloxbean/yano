package com.bloxbean.cardano.yano.catalog;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Canonical complete-artifact hashing helpers. */
public final class CatalogDigests {
    private static final String PREFIX = "sha256:";
    private static final byte[] ARTIFACT_CLOSURE_DOMAIN =
            "yano-plugin-artifact-closure-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_TREE_ENTRIES = 100_000;
    private static final int MAX_CLOSURE_ARTIFACTS = 100_000;

    private CatalogDigests() {
    }

    /**
     * Hashes one complete JAR or canonical exploded-artifact tree.
     *
     * @param artifact regular JAR file or exploded artifact directory
     * @return validated digest value and mode
     * @throws IOException if the artifact is unreadable, unsupported, changes
     *                     during hashing, or contains symbolic links
     */
    public static Digest artifact(Path artifact) throws IOException {
        BasicFileAttributes rootAttributes = Files.readAttributes(
                artifact, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (rootAttributes.isSymbolicLink()) {
            throw new IOException("Plugin artifact must not be a symbolic link");
        }
        if (rootAttributes.isRegularFile()) {
            MessageDigest digest = sha256();
            long bytesRead = 0;
            try (InputStream input = Files.newInputStream(artifact, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                        bytesRead += read;
                    }
                }
            }
            if (bytesRead != rootAttributes.size()) {
                throw new IOException("Plugin artifact changed while it was being hashed");
            }
            return new Digest(format(digest.digest()), PluginDigestMode.JAR);
        }
        if (!rootAttributes.isDirectory()) {
            throw new IOException("Plugin artifact is neither a file nor directory");
        }
        MessageDigest digest = sha256();
        List<Path> paths;
        try (var stream = Files.walk(artifact)) {
            paths = stream.limit(MAX_TREE_ENTRIES + 2L).toList();
        }
        if (paths.size() > MAX_TREE_ENTRIES + 1) {
            throw new IOException("Plugin artifact tree contains too many entries");
        }
        paths = paths.stream()
                .sorted(Comparator.comparing(path -> normalizedRelative(artifact, path)))
                .toList();
        for (Path file : paths) {
            if (file.equals(artifact)) {
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new IOException("Plugin artifact tree must not contain symbolic links");
            }
            if (!attributes.isRegularFile()) {
                continue;
            }
            byte[] relative = normalizedRelative(artifact, file).getBytes(StandardCharsets.UTF_8);
            updateLength(digest, relative.length);
            digest.update(relative);
            long size = attributes.size();
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
            long bytesRead = 0;
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                        bytesRead += read;
                    }
                }
            }
            if (bytesRead != size) {
                throw new IOException("Plugin artifact file changed while it was being hashed");
            }
        }
        return new Digest(format(digest.digest()), PluginDigestMode.ARTIFACT_TREE);
    }

    /**
     * Hashes a canonical multiset of complete artifact evidence for one bundle.
     *
     * <p>Only each artifact's digest mode and content digest participate. Input
     * order, filesystem path, file timestamp, dependency coordinate, and Gradle
     * resolution order do not. Duplicate byte-identical artifacts remain
     * represented by their multiplicity.</p>
     *
     * @param artifacts root bundle plus its complete executable dependency evidence
     * @return per-bundle artifact-closure evidence
     */
    public static Digest artifactClosure(Collection<Digest> artifacts) {
        Objects.requireNonNull(artifacts, "artifacts");
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException("artifact closure must not be empty");
        }
        if (artifacts.size() > MAX_CLOSURE_ARTIFACTS) {
            throw new IllegalArgumentException("artifact closure contains too many artifacts");
        }
        List<Digest> canonical = artifacts.stream()
                .map(value -> Objects.requireNonNull(
                        value, "artifacts must not contain null evidence"))
                .peek(value -> {
                    if (value.mode() != PluginDigestMode.JAR
                            && value.mode() != PluginDigestMode.ARTIFACT_TREE) {
                        throw new IllegalArgumentException(
                                "artifact closure inputs must be complete artifact evidence");
                    }
                })
                .sorted(Comparator.comparing((Digest value) -> value.mode().name())
                        .thenComparing(Digest::value))
                .toList();
        MessageDigest digest = sha256();
        updateLength(digest, ARTIFACT_CLOSURE_DOMAIN.length);
        digest.update(ARTIFACT_CLOSURE_DOMAIN);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(canonical.size()).array());
        for (Digest artifact : canonical) {
            updateText(digest, artifact.mode().name());
            updateText(digest, artifact.value());
        }
        return new Digest(format(digest.digest()), PluginDigestMode.ARTIFACT_CLOSURE);
    }

    /**
     * Produces legacy evidence from a provider binary class name.
     *
     * @param providerClass provider binary class name
     * @return lowercase {@code sha256:<64-hex>} digest
     */
    public static String className(String providerClass) {
        MessageDigest digest = sha256();
        digest.update(providerClass.getBytes(StandardCharsets.UTF_8));
        return format(digest.digest());
    }

    static String requireSha256(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digest must be lowercase sha256:<64-hex>");
        }
        return value;
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private static void updateLength(MessageDigest digest, int length) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }

    private static void updateText(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        updateLength(digest, bytes.length);
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String format(byte[] digest) {
        return PREFIX + HexFormat.of().formatHex(digest);
    }

    /**
     * A validated SHA-256 digest and its evidence mode.
     *
     * @param value lowercase {@code sha256:<64-hex>} digest
     * @param mode digest evidence mode
     */
    public record Digest(String value, PluginDigestMode mode) {
        /** Validates and creates digest evidence. */
        public Digest {
            value = requireSha256(value);
            mode = Objects.requireNonNull(mode, "mode");
        }
    }
}
