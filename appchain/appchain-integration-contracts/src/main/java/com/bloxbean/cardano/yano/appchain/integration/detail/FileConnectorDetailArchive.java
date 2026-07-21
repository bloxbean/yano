package com.bloxbean.cardano.yano.appchain.integration.detail;

import com.bloxbean.cardano.yano.appchain.integration.ConnectorContractException;
import com.bloxbean.cardano.yano.appchain.integration.ConnectorLimits;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent create-if-absent detail archive using hash-derived paths only.
 * It never accepts a caller-provided content path. Writes are fsynced and
 * atomically installed before a detail hash may be returned to the Effect
 * Runtime.
 *
 * <p>This reference implementation deliberately fails closed unless its
 * filesystem supports POSIX owner-only permissions, hard links, and directory
 * fsync. A pre-provisioned archive root must already be private ({@code 0700});
 * files created by the archive are {@code 0600}. Symlinks in ancestors of the
 * configured root are resolved once, while the root and every archive-owned
 * path must remain real directories. The runtime UID and private archive tree
 * are trusted against concurrent replacement by another same-UID process.</p>
 */
public final class FileConnectorDetailArchive implements ConnectorDetailArchive {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path root;
    private final Path versionRoot;
    private final Path contentRoot;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Opens or creates a private v1 archive below the configured root.
     *
     * @param root the private archive root
     * @throws IOException when the filesystem cannot provide the required safety guarantees
     */
    public FileConnectorDetailArchive(Path root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("root is required");
        }
        this.root = resolveConfiguredRoot(root);
        createRootTree(this.root);
        this.versionRoot = this.root.resolve("v1");
        createSecureDirectory(versionRoot);
        this.contentRoot = versionRoot.resolve("blake2b-256");
        createSecureDirectory(contentRoot);
        requireSecureInternalDirectory(contentRoot);
        probeFilesystemCapabilities();
    }

    @Override
    public ConnectorDetailHash archive(ConnectorDetailDocumentV1 document) throws IOException {
        ensureOpen();
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        byte[] bytes = document.encode();
        ConnectorDetailHash hash = ConnectorDetailHash.compute(bytes);
        Path target = path(hash, true);
        Path parent = target.getParent();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyExisting(target, hash, bytes);
            forceDirectory(parent);
            return hash;
        }

        Path temporary = createPrivateTempFile(parent, ".detail-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                // A hard-link install is atomic and create-if-absent; unlike
                // rename it cannot overwrite a concurrent writer's entry.
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException alreadyExists) {
                verifyExisting(target, hash, bytes);
            }
            requirePrivatePermissions(target, FILE_PERMISSIONS);
            forceDirectory(parent);
            verifyExisting(target, hash, bytes);
            return hash;
        } finally {
            // Persist removal of the temporary link as well as installation of
            // the content link. This also closes the failure path durably.
            deleteAndForceDirectory(temporary, parent);
        }
    }

    @Override
    public Optional<ConnectorDetailDocumentV1> retrieve(ConnectorDetailHash hash) throws IOException {
        ensureOpen();
        if (hash == null) {
            throw new IllegalArgumentException("hash is required");
        }
        Path target = path(hash, false);
        if (target == null) {
            return Optional.empty();
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        byte[] bytes = readBoundedRegularFile(target);
        try {
            ConnectorDetailHash actual = ConnectorDetailHash.compute(bytes);
            if (!Arrays.equals(hash.bytes(), actual.bytes())) {
                throw new IOException("detail archive hash mismatch");
            }
            return Optional.of(ConnectorDetailDocumentV1.decode(bytes));
        } catch (ConnectorContractException invalid) {
            throw new IOException("detail archive entry is invalid", invalid);
        }
    }

    /**
     * Returns the stable relative retrieval key for an authorized CLI or operations API.
     *
     * @param hash the detail commitment
     * @return the slash-separated path below the configured archive root
     */
    public static String relativeKey(ConnectorDetailHash hash) {
        if (hash == null) {
            throw new IllegalArgumentException("hash is required");
        }
        String hex = HexFormat.of().formatHex(hash.bytes());
        return "v1/blake2b-256/" + hex.substring(0, 2) + "/" + hex.substring(2) + ".cbor";
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private Path path(ConnectorDetailHash hash, boolean createParent) throws IOException {
        String hex = HexFormat.of().formatHex(hash.bytes());
        Path parent = contentRoot.resolve(hex.substring(0, 2));
        if (createParent) {
            // Validate the already-owned hierarchy before creating the shard;
            // otherwise a replaced contentRoot symlink could redirect that
            // creation outside the archive before the later full check.
            requireSecureInternalDirectory(contentRoot);
            createSecureDirectory(parent);
        } else if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            requireWithinRoot(parent);
            return null;
        }
        requireSecureInternalDirectory(parent);
        Path target = parent.resolve(hex.substring(2) + ".cbor").normalize();
        requireWithinRoot(target);
        return target;
    }

    private void verifyExisting(Path target, ConnectorDetailHash hash, byte[] expected) throws IOException {
        byte[] actual = readBoundedRegularFile(target);
        try {
            if (!Arrays.equals(expected, actual)
                    || !Arrays.equals(hash.bytes(), ConnectorDetailHash.compute(actual).bytes())) {
                throw new IOException("detail archive content conflict");
            }
        } catch (ConnectorContractException invalid) {
            throw new IOException("detail archive content conflict", invalid);
        }
    }

    private byte[] readBoundedRegularFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("detail archive entry is not a regular file");
        }
        requirePrivatePermissions(path, FILE_PERMISSIONS);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > ConnectorLimits.MAX_DETAIL_DOCUMENT_BYTES) {
                throw new IOException("detail archive entry exceeds size bound");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // bounded read
            }
            if (buffer.hasRemaining() || channel.read(ByteBuffer.allocate(1)) != -1) {
                throw new IOException("detail archive entry changed during read");
            }
            return buffer.array();
        }
    }

    private static Path resolveConfiguredRoot(Path configured) throws IOException {
        Path absolute = configured.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(absolute)) {
            throw new IOException("detail archive root must not be a symbolic link");
        }

        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("detail archive root has no existing ancestor");
        }

        Path realAncestor = existing.toRealPath();
        Path unresolved = existing.relativize(absolute);
        return realAncestor.resolve(unresolved).normalize();
    }

    private static void createRootTree(Path root) throws IOException {
        ArrayDeque<Path> missing = new ArrayDeque<>();
        Path current = root;
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.push(current);
            current = current.getParent();
            if (current == null) {
                throw new IOException("detail archive root has no existing ancestor");
            }
        }
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
            throw new IOException("detail archive ancestor is not a real directory");
        }
        while (!missing.isEmpty()) {
            Path directory = missing.pop();
            try {
                createPrivateDirectory(directory);
            } catch (FileAlreadyExistsException concurrentCreate) {
                requirePrivateDirectory(directory);
                forceDirectory(directory);
                forceDirectory(directory.getParent());
            }
        }
        requirePrivateDirectory(root);
        forceDirectory(root);
        forceDirectory(root.getParent());
    }

    private static void createSecureDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                createPrivateDirectory(directory);
                return;
            } catch (FileAlreadyExistsException concurrentCreate) {
                // Another archive instance won the create. Validate it below.
            }
        }
        requirePrivateDirectory(directory);
        forceDirectory(directory);
        forceDirectory(directory.getParent());
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        requirePosix(directory.getParent());
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        requirePrivateDirectory(directory);
        forceDirectory(directory);
        forceDirectory(directory.getParent());
    }

    private static Path createPrivateTempFile(Path parent, String prefix, String suffix) throws IOException {
        requirePosix(parent);
        Path temporary = Files.createTempFile(parent, prefix, suffix,
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        requirePrivatePermissions(temporary, FILE_PERMISSIONS);
        return temporary;
    }

    private void requireSecureInternalDirectory(Path directory) throws IOException {
        requireWithinRoot(directory);
        Path current = root;
        requirePrivateDirectory(current);
        Path relative = root.relativize(directory.toAbsolutePath().normalize());
        for (Path component : relative) {
            current = current.resolve(component);
            requirePrivateDirectory(current);
        }
    }

    private static void requirePrivateDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("detail archive path is not a real directory");
        }
        requirePrivatePermissions(directory, DIRECTORY_PERMISSIONS);
    }

    private static void requirePrivatePermissions(Path path, Set<PosixFilePermission> expected) throws IOException {
        PosixFileAttributeView view = requirePosix(path);
        Set<PosixFilePermission> actual = view.readAttributes().permissions();
        if (!actual.equals(expected)) {
            throw new IOException("detail archive path must have owner-only permissions");
        }
    }

    private static PosixFileAttributeView requirePosix(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path,
                PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("detail archive requires a POSIX-permission filesystem");
        }
        return view;
    }

    private void probeFilesystemCapabilities() throws IOException {
        Path source = null;
        Path link = null;
        IOException failure = null;
        try {
            source = createPrivateTempFile(contentRoot, ".archive-probe-", ".tmp");
            link = contentRoot.resolve(source.getFileName() + ".link");
            try (FileChannel channel = FileChannel.open(source,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(ByteBuffer.wrap(new byte[]{0}));
                channel.force(true);
            }
            Files.createLink(link, source);
            requirePrivatePermissions(link, FILE_PERMISSIONS);
            forceDirectory(contentRoot);
        } catch (IOException | UnsupportedOperationException exception) {
            failure = new IOException(
                    "detail archive requires hard-link create-if-absent and directory fsync", exception);
        } finally {
            try {
                if (link != null) {
                    Files.deleteIfExists(link);
                }
                if (source != null) {
                    Files.deleteIfExists(source);
                }
                forceDirectory(contentRoot);
            } catch (IOException cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireWithinRoot(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("detail archive path escaped configured root");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void deleteAndForceDirectory(Path temporary, Path parent) throws IOException {
        IOException failure = null;
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException deleteFailure) {
            failure = deleteFailure;
        }
        try {
            forceDirectory(parent);
        } catch (IOException forceFailure) {
            if (failure == null) {
                failure = forceFailure;
            } else {
                failure.addSuppressed(forceFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("detail archive is closed");
        }
    }
}
