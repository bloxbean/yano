package com.bloxbean.cardano.yano.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Private, short-lived evidence boundary used by {@link PluginArtifactScanner}.
 *
 * <p>Accepted plugin metadata and its digest are both read from this copy. The
 * source is checked before and after copying. Explicit/offline inputs are
 * always captured before classification; only the build-time classpath scan
 * may probe ordinary dependencies first and avoid copying them.</p>
 */
final class PluginArtifactSnapshot implements AutoCloseable {
    private static final String CHANGED_DURING_CAPTURE =
            "Plugin artifact changed while its immutable scan snapshot was captured";

    private final Path temporaryRoot;
    private final Path artifact;
    private boolean closed;

    private PluginArtifactSnapshot(Path temporaryRoot, Path artifact) {
        this.temporaryRoot = temporaryRoot;
        this.artifact = artifact;
    }

    static SourceStamp stamp(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        BasicFileAttributes root = attributes(source);
        if (root.isSymbolicLink()) {
            throw new IOException("Plugin artifact must not be a symbolic link");
        }
        if (root.isRegularFile()) {
            advanceCapturedBytes(source, 0, root.size());
            return new SourceStamp(SourceKind.FILE,
                    Map.of("", EntryStamp.from(root, SourceKind.FILE)));
        }
        if (!root.isDirectory()) {
            throw new IOException("Plugin artifact is neither a regular file nor directory");
        }

        List<Path> paths;
        try (var stream = Files.walk(source)) {
            paths = stream.limit(PluginArtifactScanner.MAX_ARTIFACT_ENTRIES + 2L)
                    .toList();
        }
        if (paths.size() > PluginArtifactScanner.MAX_ARTIFACT_ENTRIES + 1) {
            throw new IOException("Plugin artifact tree contains too many entries");
        }
        paths = paths.stream()
                .sorted(Comparator.comparing(path -> relativeName(source, path)))
                .toList();

        Map<String, EntryStamp> entries = new LinkedHashMap<>();
        long totalNameCharacters = 0;
        long totalBytes = 0;
        for (Path path : paths) {
            String relative = relativeName(source, path);
            if (relative.length() > PluginArtifactScanner.MAX_ENTRY_NAME_LENGTH) {
                throw new IOException("Plugin artifact contains an entry name longer than "
                        + PluginArtifactScanner.MAX_ENTRY_NAME_LENGTH + " characters");
            }
            totalNameCharacters += relative.length();
            if (totalNameCharacters
                    > PluginArtifactScanner.MAX_TOTAL_ENTRY_NAME_CHARACTERS) {
                throw new IOException("Plugin artifact entry names exceed "
                        + PluginArtifactScanner.MAX_TOTAL_ENTRY_NAME_CHARACTERS
                        + " total characters");
            }
            BasicFileAttributes attributes = attributes(path);
            if (attributes.isSymbolicLink()) {
                throw new PluginCatalogException(
                        "Invalid plugin artifact: exploded artifact must not contain symbolic links");
            }
            SourceKind kind;
            if (attributes.isDirectory()) {
                kind = SourceKind.DIRECTORY;
            } else if (attributes.isRegularFile()) {
                kind = SourceKind.FILE;
                totalBytes = advanceCapturedBytes(
                        source, totalBytes, attributes.size());
            } else {
                throw new IOException(
                        "Plugin artifact tree must contain only regular files and directories");
            }
            entries.put(relative, EntryStamp.from(attributes, kind));
        }
        return new SourceStamp(SourceKind.DIRECTORY, Map.copyOf(entries));
    }

    static PluginArtifactSnapshot capture(
            Path source,
            SourceStamp expected,
            CaptureObserver observer
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observer, "observer");

        SourceStamp before = stamp(source);
        if (!expected.equals(before)) {
            throw new IOException(CHANGED_DURING_CAPTURE);
        }

        Path temporaryRoot = Files.createTempDirectory("yano-plugin-artifact-scan-");
        Path snapshotPath = temporaryRoot.resolve(
                before.kind() == SourceKind.FILE ? "artifact.jar" : "artifact");
        boolean captured = false;
        try {
            CaptureBudget budget = new CaptureBudget(source);
            if (before.kind() == SourceKind.FILE) {
                copyStableFile(source, snapshotPath, before.entries().get(""), budget);
            } else {
                copyStableDirectory(source, snapshotPath, before, budget);
            }
            observer.afterCopy(source, snapshotPath);
            if (!before.equals(stamp(source))) {
                throw new IOException(CHANGED_DURING_CAPTURE);
            }
            captured = true;
            return new PluginArtifactSnapshot(temporaryRoot, snapshotPath);
        } finally {
            if (!captured) {
                deleteRecursively(temporaryRoot);
            }
        }
    }

    Path artifact() {
        return artifact;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            deleteRecursively(temporaryRoot);
        }
    }

    private static void copyStableDirectory(
            Path source,
            Path target,
            SourceStamp expected,
            CaptureBudget budget
    ) throws IOException {
        Files.createDirectory(target);
        List<Map.Entry<String, EntryStamp>> entries = new ArrayList<>(
                expected.entries().entrySet());
        entries.sort(Comparator
                .comparingInt((Map.Entry<String, EntryStamp> entry) -> depth(entry.getKey()))
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, EntryStamp> entry : entries) {
            String relative = entry.getKey();
            if (relative.isEmpty()) {
                continue;
            }
            Path input = source.resolve(relative);
            Path output = target.resolve(relative);
            if (entry.getValue().kind() == SourceKind.DIRECTORY) {
                if (!entry.getValue().equals(entryStamp(input))) {
                    throw new IOException(CHANGED_DURING_CAPTURE);
                }
                Files.createDirectory(output);
            } else {
                Files.createDirectories(output.getParent());
                copyStableFile(input, output, entry.getValue(), budget);
            }
        }
    }

    private static void copyStableFile(
            Path source,
            Path target,
            EntryStamp expected,
            CaptureBudget budget
    ) throws IOException {
        if (!expected.equals(entryStamp(source))) {
            throw new IOException(CHANGED_DURING_CAPTURE);
        }
        long copied = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) {
                    if (copied > expected.size() - read) {
                        throw new IOException(CHANGED_DURING_CAPTURE);
                    }
                    budget.advance(read);
                    output.write(buffer, 0, read);
                    copied += read;
                }
            }
        }
        if (copied != expected.size() || !expected.equals(entryStamp(source))) {
            throw new IOException(CHANGED_DURING_CAPTURE);
        }
    }

    /**
     * Advances the production snapshot budget without materializing a
     * one-gibibyte test artifact. The same overflow-safe check is used during
     * initial stamping and streaming copy.
     */
    static long advanceCapturedBytes(Path source, long capturedBytes, long bytesRead)
            throws IOException {
        Objects.requireNonNull(source, "source");
        if (capturedBytes < 0 || bytesRead < 0) {
            throw new IllegalArgumentException("Captured plugin artifact bytes must be non-negative");
        }
        if (capturedBytes > PluginArtifactScanner.MAX_ARTIFACT_BYTES - bytesRead) {
            throw new IOException("Plugin artifact '" + source.getFileName()
                    + "' exceeds immutable snapshot byte limit of "
                    + PluginArtifactScanner.MAX_ARTIFACT_BYTES + " bytes");
        }
        return capturedBytes + bytesRead;
    }

    private static EntryStamp entryStamp(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (attributes.isSymbolicLink()) {
            throw new IOException("Plugin artifact tree must not contain symbolic links");
        }
        SourceKind kind;
        if (attributes.isDirectory()) {
            kind = SourceKind.DIRECTORY;
        } else if (attributes.isRegularFile()) {
            kind = SourceKind.FILE;
        } else {
            throw new IOException(
                    "Plugin artifact tree must contain only regular files and directories");
        }
        return EntryStamp.from(attributes, kind);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static int depth(String relative) {
        if (relative.isEmpty()) {
            return 0;
        }
        int depth = 1;
        for (int index = 0; index < relative.length(); index++) {
            if (relative.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString()
                .replace(path.getFileSystem().getSeparator(), "/");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        IOException failure = null;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException deletionFailure) {
                if (failure == null) {
                    failure = deletionFailure;
                } else {
                    failure.addSuppressed(deletionFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface CaptureObserver {
        CaptureObserver NOOP = (source, snapshot) -> { };

        void afterCopy(Path source, Path snapshot) throws IOException;
    }

    private static final class CaptureBudget {
        private final Path source;
        private long capturedBytes;

        private CaptureBudget(Path source) {
            this.source = source;
        }

        private void advance(long bytesRead) throws IOException {
            capturedBytes = advanceCapturedBytes(source, capturedBytes, bytesRead);
        }
    }

    record SourceStamp(SourceKind kind, Map<String, EntryStamp> entries) {
        SourceStamp {
            kind = Objects.requireNonNull(kind, "kind");
            entries = Map.copyOf(entries);
        }
    }

    private record EntryStamp(
            SourceKind kind,
            Object fileKey,
            long size,
            FileTime lastModifiedTime,
            FileTime creationTime
    ) {
        private static EntryStamp from(
                BasicFileAttributes attributes,
                SourceKind kind
        ) {
            return new EntryStamp(kind, attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime(), attributes.creationTime());
        }
    }

    enum SourceKind {
        FILE,
        DIRECTORY
    }
}
