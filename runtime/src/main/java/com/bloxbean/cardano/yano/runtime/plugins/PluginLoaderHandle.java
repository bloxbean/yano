package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSourceCategory;
import com.bloxbean.cardano.yano.catalog.CatalogDigests;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicitly owned class loader and artifact list for one catalog lifetime.
 *
 * <p>The parent is a trusted application boundary, not a sandbox boundary.
 * Directory loaders are parent-first, so host classes and ordinary resources
 * may intentionally shadow selected plugin definitions. Operator-controlled
 * plugin JARs must not also be placed on that parent class path.</p>
 */
public final class PluginLoaderHandle implements AutoCloseable {
    /** Defensive upper bound for one directory-deployed plugin artifact. */
    static final long MAX_PLUGIN_JAR_BYTES = 1024L * 1024L * 1024L;
    /** Defensive upper bound for one plugin-directory deployment. */
    static final int MAX_PLUGIN_JARS = 256;
    /** Bound directory traversal even when entries are not plugin JARs. */
    static final int MAX_PLUGIN_DIRECTORY_ENTRIES = 1_024;
    /** Defensive upper bound for all private JAR snapshots owned by one loader. */
    static final long MAX_PLUGIN_SNAPSHOT_BYTES = 4L * 1024L * 1024L * 1024L;

    static final String SNAPSHOT_DIRECTORY_PREFIX = "yano-plugin-snapshot-v1-";
    static final String SNAPSHOT_LEASE_FILE = ".yano-plugin-snapshot.lease";
    static final String SNAPSHOT_LEASE_MARKER = "yano-plugin-snapshot-v1\n";
    static final Duration SNAPSHOT_ORPHAN_GRACE = Duration.ofMinutes(10);
    static final int MAX_ORPHAN_SNAPSHOT_CANDIDATES = 256;

    /**
     * Directory handles begin with the parent only. The immutable catalog scan
     * later installs a loader containing only policy-selected snapshot JARs.
     */
    private volatile ClassLoader classLoader;
    private final ClassLoader embeddedIndexLoader;
    private final List<Path> artifacts;
    private final Map<Path, CatalogDigests.Digest> artifactOrderingDigests;
    private final Map<Path, String> artifactSourceNames;
    private final PluginSourceCategory source;
    private final PluginDiscoveryMode discoveryMode;
    private final AutoCloseable ownedLoader;
    private final DirectoryLoaderOwnership directoryLoaderOwnership;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean runtimeClaimed = new AtomicBoolean();

    private PluginLoaderHandle(ClassLoader classLoader,
                               ClassLoader embeddedIndexLoader,
                               List<Path> artifacts,
                               Map<Path, CatalogDigests.Digest> artifactOrderingDigests,
                               Map<Path, String> artifactSourceNames,
                               PluginSourceCategory source,
                               PluginDiscoveryMode discoveryMode,
                               AutoCloseable ownedLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.embeddedIndexLoader = Objects.requireNonNull(embeddedIndexLoader, "embeddedIndexLoader");
        this.artifacts = List.copyOf(artifacts);
        this.artifactOrderingDigests = Collections.unmodifiableMap(
                new LinkedHashMap<>(artifactOrderingDigests));
        this.artifactSourceNames = Collections.unmodifiableMap(
                new LinkedHashMap<>(artifactSourceNames));
        if (!this.artifactOrderingDigests.keySet().equals(Set.copyOf(this.artifacts))) {
            throw new IllegalArgumentException(
                    "Plugin artifact ordering evidence must identify every artifact exactly once");
        }
        if (!this.artifactSourceNames.keySet().equals(Set.copyOf(this.artifacts))) {
            throw new IllegalArgumentException(
                    "Plugin artifact source labels must identify every artifact exactly once");
        }
        this.source = Objects.requireNonNull(source, "source");
        this.discoveryMode = Objects.requireNonNull(discoveryMode, "discoveryMode");
        this.ownedLoader = ownedLoader;
        this.directoryLoaderOwnership = ownedLoader instanceof DirectoryLoaderOwnership ownership
                ? ownership : null;
    }

    /**
     * Creates a compatibility handle for a library embedder. A missing
     * aggregate index is allowed and unindexed ServiceLoader providers are
     * represented as legacy bundles.
     */
    public static PluginLoaderHandle classpath(ClassLoader loader) {
        ClassLoader effective = effective(loader);
        return new PluginLoaderHandle(effective, effective, List.of(), Map.of(), Map.of(),
                PluginSourceCategory.CLASSPATH,
                PluginDiscoveryMode.LIBRARY_COMPATIBILITY, null);
    }

    /** Creates a packaged-JVM handle that requires one authoritative index. */
    public static PluginLoaderHandle packagedClasspath(ClassLoader loader) {
        ClassLoader effective = effective(loader);
        return new PluginLoaderHandle(effective, effective, List.of(), Map.of(), Map.of(),
                PluginSourceCategory.CLASSPATH, PluginDiscoveryMode.PACKAGED_JVM, null);
    }

    /** Creates a native-image handle that requires one build-time index. */
    public static PluginLoaderHandle nativeClasspath(ClassLoader loader) {
        ClassLoader effective = effective(loader);
        return new PluginLoaderHandle(effective, effective, List.of(), Map.of(), Map.of(),
                PluginSourceCategory.NATIVE, PluginDiscoveryMode.NATIVE, null);
    }

    /**
     * Creates a directory handle for a compatibility library embedder.
     * Packaged applications should use {@link #packagedDirectory(Path, ClassLoader)}.
     * The parent must be a trusted host loader without directory-deployed
     * plugin artifacts; parent-first loading does not isolate hostile code.
     */
    public static PluginLoaderHandle directory(Path directory, ClassLoader parent) {
        return directory(directory, parent, PluginDiscoveryMode.LIBRARY_COMPATIBILITY);
    }

    /** Creates a packaged-JVM directory handle that requires one base index. */
    public static PluginLoaderHandle packagedDirectory(Path directory, ClassLoader parent) {
        return directory(directory, parent, PluginDiscoveryMode.PACKAGED_JVM);
    }

    private static PluginLoaderHandle directory(Path directory,
                                                ClassLoader parent,
                                                PluginDiscoveryMode discoveryMode) {
        Objects.requireNonNull(directory, "directory");
        ClassLoader effectiveParent = effective(parent);
        // Reclaim verifiable stale snapshots even when this particular plugin
        // directory was removed after the abnormal exit.
        cleanupOrphanedSnapshotDirectories();
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException("Plugin directory must not be a symbolic link");
        }
        if (!Files.exists(directory)) {
            return classpathForMode(effectiveParent, discoveryMode);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Plugin directory is not a directory");
        }
        DirectoryIdentity directoryIdentity = directoryIdentity(directory);
        List<SourceArtifact> discoveredJars = new ArrayList<>();
        long snapshotBytes = 0;
        int scannedEntries = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                if (++scannedEntries > MAX_PLUGIN_DIRECTORY_ENTRIES) {
                    throw new IllegalStateException(
                            "Plugin directory contains too many entries (limit="
                                    + MAX_PLUGIN_DIRECTORY_ENTRIES + ")");
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalStateException(
                            "Plugin directory must not contain symbolic-link entries");
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.getFileName().toString().endsWith(".jar")) {
                    continue;
                }
                SourceArtifact artifact = sourceArtifact(path.toAbsolutePath().normalize());
                if (artifact.identity().size() > MAX_PLUGIN_SNAPSHOT_BYTES - snapshotBytes) {
                    validateSnapshotBudget(discoveredJars.size() + 1,
                            MAX_PLUGIN_SNAPSHOT_BYTES + 1);
                }
                snapshotBytes += artifact.identity().size();
                validateSnapshotBudget(discoveredJars.size() + 1, snapshotBytes);
                discoveredJars.add(artifact);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Plugin directory could not be listed", e);
        }
        discoveredJars.sort(Comparator.comparing(artifact -> artifact.path().toString()));
        if (discoveredJars.isEmpty()) {
            return classpathForMode(effectiveParent, discoveryMode);
        }
        SnapshotLease snapshotLease = createPrivateSnapshotDirectory();
        Path snapshotDirectory = snapshotLease.directory();
        try {
            List<DigestedArtifact> orderedArtifacts = new ArrayList<>(discoveredJars.size());
            for (int index = 0; index < discoveredJars.size(); index++) {
                SourceArtifact sourceArtifact = discoveredJars.get(index);
                Path snapshotArtifact = snapshotDirectory.resolve("%05d.jar".formatted(index));
                CatalogDigests.Digest digest = snapshotArtifact(sourceArtifact, snapshotArtifact);
                orderedArtifacts.add(new DigestedArtifact(
                        snapshotArtifact,
                        sourceArtifact.path().getFileName().toString(),
                        sourceArtifact.path().toString(),
                        digest));
            }
            discoveredJars.forEach(PluginLoaderHandle::verifySourceArtifactUnchanged);
            verifyDirectoryIdentityUnchanged(directory, directoryIdentity);
            orderedArtifacts.sort(Comparator.comparing(
                            (DigestedArtifact artifact) -> artifact.digest().value())
                    .thenComparing(DigestedArtifact::sourcePath));
            rejectDigestCollisions(orderedArtifacts);
            // Snapshot URLs are observable through provider CodeSource and
            // resource URLs. Derive each leaf name from content so inserting
            // or renaming a policy-filtered JAR cannot perturb a selected
            // provider's observable snapshot filename.
            List<DigestedArtifact> contentAddressedArtifacts = new ArrayList<>(
                    orderedArtifacts.size());
            for (DigestedArtifact artifact : orderedArtifacts) {
                Path contentAddressed = snapshotDirectory.resolve(
                        contentAddressedSnapshotName(artifact.digest()));
                Files.move(artifact.path(), contentAddressed);
                contentAddressedArtifacts.add(new DigestedArtifact(
                        contentAddressed.toRealPath(), artifact.sourceName(),
                        artifact.sourcePath(), artifact.digest()));
            }
            orderedArtifacts = contentAddressedArtifacts;
            List<Path> jars = orderedArtifacts.stream().map(DigestedArtifact::path).toList();
            Map<Path, CatalogDigests.Digest> orderingDigests = new LinkedHashMap<>();
            Map<Path, String> sourceNames = new LinkedHashMap<>();
            orderedArtifacts.forEach(artifact -> {
                orderingDigests.put(artifact.path(), artifact.digest());
                sourceNames.put(artifact.path(), artifact.sourceName());
            });
            DirectoryLoaderOwnership ownership = new DirectoryLoaderOwnership(snapshotLease);
            // Do not put installed bytes on an executable loader before bundle
            // policy has selected them. Direct artifact scanning and the
            // isolated type-validation loader operate on the snapshots first.
            return new PluginLoaderHandle(effectiveParent, effectiveParent, jars, orderingDigests,
                    sourceNames, PluginSourceCategory.DIRECTORY, discoveryMode, ownership);
        } catch (IOException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "Captured plugin snapshot path could not be resolved", failure);
            cleanupAfterConstructionFailure(null, snapshotLease, wrapped);
            throw wrapped;
        } catch (RuntimeException | Error failure) {
            cleanupAfterConstructionFailure(null, snapshotLease, failure);
            throw failure;
        }
    }

    /**
     * Installs the terminal executable directory loader after every captured
     * artifact has been scanned, type-validated in isolation and policy
     * classified. Unselected artifacts remain captured for inventory evidence
     * but never become lookup URLs on this loader.
     */
    void activateDirectoryArtifacts(Set<Path> executableArtifacts) {
        Objects.requireNonNull(executableArtifacts, "executableArtifacts");
        synchronized (closed) {
            if (closed.get()) {
                throw new IllegalStateException("Plugin loader handle is already closed");
            }
            if (!runtimeClaimed.get()) {
                throw new IllegalStateException(
                        "Directory plugin loader must belong to a runtime environment");
            }
            if (directoryLoaderOwnership == null) {
                if (!executableArtifacts.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Only a directory plugin handle may activate artifacts");
                }
                return;
            }
            Set<Path> requested = Set.copyOf(executableArtifacts);
            if (!Set.copyOf(artifacts).containsAll(requested)) {
                throw new IllegalArgumentException(
                        "Executable plugin artifacts must come from the captured snapshot");
            }
            URLClassLoader loader = null;
            try {
                List<URL> urls = new ArrayList<>(requested.size());
                for (Path artifact : artifacts) {
                    if (requested.contains(artifact)) {
                        urls.add(artifact.toUri().toURL());
                    }
                }
                loader = new URLClassLoader(urls.toArray(URL[]::new), embeddedIndexLoader);
                directoryLoaderOwnership.install(loader);
                classLoader = loader;
            } catch (IOException | RuntimeException | Error failure) {
                Throwable outcome = failure;
                if (loader != null) {
                    try {
                        loader.close();
                    } catch (Throwable cleanup) {
                        outcome = LifecycleFailures.merge(outcome, cleanup);
                    }
                }
                LifecycleFailures.rethrowIfProcessFatalReachable(outcome);
                if (outcome instanceof Error error) {
                    throw error;
                }
                if (outcome instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(
                        "Selected plugin snapshot URLs could not be activated", outcome);
            }
        }
    }

    static void validateSnapshotBudget(int jarCount, long aggregateBytes) {
        if (jarCount > MAX_PLUGIN_JARS) {
            throw new IllegalStateException("Plugin directory contains more than "
                    + MAX_PLUGIN_JARS + " JARs");
        }
        if (aggregateBytes > MAX_PLUGIN_SNAPSHOT_BYTES) {
            throw new IllegalStateException("Plugin directory JARs exceed the aggregate snapshot "
                    + "limit of " + MAX_PLUGIN_SNAPSHOT_BYTES + " bytes");
        }
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    /**
     * Immutable host/application loader. Bootstrap resources must use this
     * loader rather than the executable plugin projection so denied directory
     * artifacts cannot influence node configuration. The caller is responsible
     * for keeping this trusted parent free of operator plugin-directory JARs.
     */
    public ClassLoader hostClassLoader() {
        return embeddedIndexLoader;
    }

    public List<Path> artifacts() {
        return artifacts;
    }

    CatalogDigests.Digest artifactOrderingDigest(Path artifact) {
        CatalogDigests.Digest digest = artifactOrderingDigests.get(
                Objects.requireNonNull(artifact, "artifact"));
        if (digest == null) {
            throw new IllegalStateException(
                    "Plugin artifact has no class-loader ordering evidence");
        }
        return digest;
    }

    String artifactSourceName(Path artifact) {
        String sourceName = artifactSourceNames.get(Objects.requireNonNull(artifact, "artifact"));
        if (sourceName == null) {
            throw new IllegalStateException("Plugin artifact has no source diagnostic label");
        }
        return sourceName;
    }

    ClassLoader embeddedIndexLoader() {
        return embeddedIndexLoader;
    }

    public PluginSourceCategory source() {
        return source;
    }

    /** Returns the discovery contract selected by the embedding. */
    public PluginDiscoveryMode discoveryMode() {
        return discoveryMode;
    }

    public boolean ownsLoader() {
        return ownedLoader != null;
    }

    /**
     * Transfer the handle's terminal lifetime to one plugin environment.
     * This is distinct from class-loader ownership: even classpath handles
     * must have one unambiguous environment owner.
     */
    void claimRuntimeOwnership() {
        synchronized (closed) {
            if (closed.get()) {
                throw new IllegalStateException("Plugin loader handle is already closed");
            }
            if (!runtimeClaimed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "Plugin loader handle already belongs to a runtime environment");
            }
        }
    }

    /**
     * CDI/bootstrap fallback: close only when runtime assembly never accepted
     * the handle. A claimed handle may intentionally remain alive after an
     * unsafe runtime drain; closing it here would violate the callback fence.
     */
    public void closeIfUnclaimed() {
        synchronized (closed) {
            if (runtimeClaimed.get()) {
                return;
            }
            closeOwnedLoader();
        }
    }

    @Override
    public void close() {
        synchronized (closed) {
            if (closed.get()) {
                return;
            }
            if (runtimeClaimed.get()) {
                throw new IllegalStateException(
                        "Plugin loader handle belongs to a runtime environment");
            }
            closeOwnedLoader();
        }
    }

    /** Terminal close available only to the environment that claimed this handle. */
    void closeClaimed() {
        synchronized (closed) {
            if (closed.get()) {
                return;
            }
            if (!runtimeClaimed.get()) {
                throw new IllegalStateException(
                        "Plugin loader handle has no runtime environment owner");
            }
            closeOwnedLoader();
        }
    }

    private void closeOwnedLoader() {
        if (!closed.compareAndSet(false, true) || ownedLoader == null) {
            return;
        }
        try {
            ownedLoader.close();
        } catch (Exception e) {
            throw new IllegalStateException("Plugin class loader/artifact cleanup failed", e);
        }
    }

    private static ClassLoader effective(ClassLoader loader) {
        if (loader != null) {
            return loader;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : PluginLoaderHandle.class.getClassLoader();
    }

    private static PluginLoaderHandle classpathForMode(
            ClassLoader loader,
            PluginDiscoveryMode discoveryMode
    ) {
        return switch (discoveryMode) {
            case LIBRARY_COMPATIBILITY -> classpath(loader);
            case PACKAGED_JVM -> packagedClasspath(loader);
            case NATIVE -> nativeClasspath(loader);
        };
    }

    private static DirectoryIdentity directoryIdentity(Path directory) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IllegalArgumentException("Plugin directory is not a directory");
            }
            return new DirectoryIdentity(attributes.fileKey(), attributes.lastModifiedTime());
        } catch (IOException e) {
            throw new IllegalStateException("Plugin directory identity could not be read", e);
        }
    }

    private static void verifyDirectoryIdentityUnchanged(
            Path directory,
            DirectoryIdentity expected
    ) {
        DirectoryIdentity actual = directoryIdentity(directory);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Plugin directory changed while artifacts were captured");
        }
    }

    private static SnapshotLease createPrivateSnapshotDirectory() {
        Path directory = null;
        FileChannel leaseChannel = null;
        FileLock leaseLock = null;
        try {
            try {
                directory = Files.createTempDirectory(
                        SNAPSHOT_DIRECTORY_PREFIX,
                        PosixFilePermissions.asFileAttribute(EnumSet.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE)));
            } catch (UnsupportedOperationException e) {
                directory = Files.createTempDirectory(SNAPSHOT_DIRECTORY_PREFIX);
            }
            Path leaseFile = directory.resolve(SNAPSHOT_LEASE_FILE);
            leaseChannel = FileChannel.open(leaseFile,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            ByteBuffer marker = ByteBuffer.wrap(
                    SNAPSHOT_LEASE_MARKER.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            while (marker.hasRemaining()) {
                leaseChannel.write(marker);
            }
            leaseChannel.force(true);
            leaseLock = leaseChannel.tryLock();
            if (leaseLock == null) {
                throw new IOException("Private plugin snapshot lease could not be acquired");
            }
            return new SnapshotLease(directory, leaseFile, leaseChannel, leaseLock);
        } catch (IOException | RuntimeException | Error e) {
            Throwable failure = e;
            failure = closeAfterFailure(leaseLock, failure);
            failure = closeAfterFailure(leaseChannel, failure);
            failure = deleteAfterFailure(directory, failure);
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Private plugin snapshot directory could not be created", e);
        }
    }

    /**
     * Best-effort reclamation for snapshots left by an abnormal process exit.
     * A directory is touched only when it has our marker, is older than the
     * creation-race grace period, and its OS lease can be acquired exclusively.
     */
    static int cleanupOrphanedSnapshotDirectories() {
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir", "."))
                .toAbsolutePath().normalize();
        return cleanupOrphanedSnapshotDirectories(
                temporaryRoot, MAX_ORPHAN_SNAPSHOT_CANDIDATES);
    }

    /** Package-private bounded-root seam for reclamation tests. */
    static int cleanupOrphanedSnapshotDirectories(
            Path temporaryRoot,
            int maximumCandidates
    ) {
        Objects.requireNonNull(temporaryRoot, "temporaryRoot");
        if (maximumCandidates < 1) {
            throw new IllegalArgumentException("maximumCandidates must be positive");
        }
        int removed = 0;
        int inspected = 0;
        try (DirectoryStream<Path> candidates = Files.newDirectoryStream(
                temporaryRoot, SNAPSHOT_DIRECTORY_PREFIX + "*")) {
            var iterator = candidates.iterator();
            while (inspected < maximumCandidates && iterator.hasNext()) {
                Path candidate = iterator.next();
                inspected++;
                if (cleanupOrphanedSnapshotDirectory(candidate)) {
                    removed++;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Orphan reclamation must never make a valid plugin deployment fail.
        }
        return removed;
    }

    private static boolean cleanupOrphanedSnapshotDirectory(Path directory) {
        return cleanupOrphanedSnapshotDirectory(directory, (ignoredDirectory, ignoredLease) -> { });
    }

    /**
     * Package-private checkpoint for deterministic race testing. Production
     * orphan cleanup always supplies a no-op checkpoint.
     */
    static boolean cleanupOrphanedSnapshotDirectory(
            Path directory,
            SnapshotCleanupCheckpoint checkpoint
    ) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        FileTime cutoff = FileTime.from(Instant.now().minus(SNAPSHOT_ORPHAN_GRACE));
        try {
            BasicFileAttributes directoryBefore = Files.readAttributes(
                    directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!directoryBefore.isDirectory() || directoryBefore.isSymbolicLink()
                    || directoryBefore.lastModifiedTime().compareTo(cutoff) > 0) {
                return false;
            }
            Path leaseFile = directory.resolve(SNAPSHOT_LEASE_FILE);
            BasicFileAttributes leaseBefore = Files.readAttributes(
                    leaseFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!leaseBefore.isRegularFile() || leaseBefore.isSymbolicLink()
                    || leaseBefore.size() != SNAPSHOT_LEASE_MARKER.length()) {
                return false;
            }
            try (FileChannel channel = FileChannel.open(leaseFile,
                    StandardOpenOption.READ, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                if (!hasSnapshotLeaseMarker(channel)) {
                    return false;
                }
                try (FileLock ignored = channel.tryLock()) {
                    if (ignored == null) {
                        return false;
                    }
                    checkpoint.afterLeaseAcquired(directory, leaseFile);
                    BasicFileAttributes directoryAfter = Files.readAttributes(
                            directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    BasicFileAttributes leaseAfter = Files.readAttributes(
                            leaseFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!sameIdentity(directoryBefore, directoryAfter)
                            || !sameIdentity(leaseBefore, leaseAfter)
                            || directoryAfter.lastModifiedTime().compareTo(cutoff) > 0) {
                        return false;
                    }
                    deleteSnapshotPayload(directory, leaseFile);
                }
            } catch (OverlappingFileLockException ignored) {
                return false;
            }
            Files.deleteIfExists(leaseFile);
            Files.deleteIfExists(directory);
            return !Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasSnapshotLeaseMarker(FileChannel channel) throws IOException {
        byte[] expected = SNAPSHOT_LEASE_MARKER.getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer marker = ByteBuffer.allocate(expected.length + 1);
        channel.position(0);
        while (marker.hasRemaining() && channel.read(marker) > 0) {
            // Read through the bounded marker buffer.
        }
        marker.flip();
        byte[] actual = new byte[marker.remaining()];
        marker.get(actual);
        return Arrays.equals(expected, actual);
    }

    private static boolean sameIdentity(
            BasicFileAttributes expected,
            BasicFileAttributes actual
    ) {
        return expected.isDirectory() == actual.isDirectory()
                && expected.isRegularFile() == actual.isRegularFile()
                && Objects.equals(expected.fileKey(), actual.fileKey())
                && expected.size() == actual.size()
                && expected.lastModifiedTime().equals(actual.lastModifiedTime());
    }

    private static SourceArtifact sourceArtifact(Path source) {
        return new SourceArtifact(source, sourceIdentity(source));
    }

    private static SourceIdentity sourceIdentity(Path source) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException("Plugin JAR '" + source.getFileName()
                    + "' could not be inspected before capture", e);
        }
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IllegalStateException("Plugin JAR '" + source.getFileName()
                    + "' is no longer a regular non-symbolic-link file");
        }
        if (attributes.size() > MAX_PLUGIN_JAR_BYTES) {
            throw new IllegalStateException("Plugin JAR '" + source.getFileName()
                    + "' exceeds " + MAX_PLUGIN_JAR_BYTES + " bytes");
        }
        return new SourceIdentity(
                attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    }

    private static void verifySourceArtifactUnchanged(SourceArtifact source) {
        SourceIdentity actual = sourceIdentity(source.path());
        if (!source.identity().equals(actual)) {
            throw new IllegalStateException("Plugin JAR '" + source.path().getFileName()
                    + "' changed while directory artifacts were captured");
        }
    }

    private static CatalogDigests.Digest snapshotArtifact(
            SourceArtifact sourceArtifact,
            Path target
    ) {
        verifySourceArtifactUnchanged(sourceArtifact);
        Path source = sourceArtifact.path();

        MessageDigest capturedDigest = sha256();
        long copied = 0;
        try (FileChannel input = FileChannel.open(
                     source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel output = FileChannel.open(
                     target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                copied = advanceCapturedPluginJarBytes(source, copied, read);
                buffer.flip();
                capturedDigest.update(buffer.asReadOnlyBuffer());
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
            output.force(true);
        } catch (IOException e) {
            throw new IllegalStateException("Plugin JAR '" + source.getFileName()
                    + "' could not be captured", e);
        }
        verifySourceArtifactUnchanged(sourceArtifact);

        CatalogDigests.Digest expected = new CatalogDigests.Digest(
                "sha256:" + HexFormat.of().formatHex(capturedDigest.digest()),
                PluginDigestMode.JAR);
        try {
            CatalogDigests.Digest actual = CatalogDigests.artifact(target);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Plugin JAR '" + source.getFileName()
                        + "' snapshot bytes differ from the captured source bytes");
            }
            makeSnapshotReadOnly(target);
            return actual;
        } catch (IOException e) {
            throw new IllegalStateException("Plugin JAR '" + source.getFileName()
                    + "' snapshot could not be verified", e);
        }
    }

    /**
     * Advances the byte count used by the streaming capture loop. Keeping the
     * boundary check here makes the production limit directly testable without
     * materializing or reading a one-gibibyte artifact.
     */
    static long advanceCapturedPluginJarBytes(Path source, long copiedBytes, int bytesRead) {
        Objects.requireNonNull(source, "source");
        if (copiedBytes < 0 || bytesRead <= 0) {
            throw new IllegalArgumentException("Captured plugin byte counts must be positive");
        }
        if (copiedBytes > MAX_PLUGIN_JAR_BYTES - bytesRead) {
            throw new IllegalStateException("Plugin JAR '" + source.getFileName()
                    + "' exceeds " + MAX_PLUGIN_JAR_BYTES + " bytes while captured");
        }
        return copiedBytes + bytesRead;
    }

    private static void makeSnapshotReadOnly(Path snapshot) throws IOException {
        try {
            Files.setPosixFilePermissions(snapshot,
                    EnumSet.of(PosixFilePermission.OWNER_READ));
        } catch (UnsupportedOperationException ignored) {
            // The private, randomly named parent remains the ownership boundary.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void rejectDigestCollisions(List<DigestedArtifact> artifacts) {
        for (int index = 1; index < artifacts.size(); index++) {
            DigestedArtifact previous = artifacts.get(index - 1);
            DigestedArtifact current = artifacts.get(index);
            if (!previous.digest().equals(current.digest())) {
                continue;
            }
            try {
                if (Files.mismatch(previous.path(), current.path()) == -1) {
                    throw new IllegalStateException(
                            "Duplicate byte-identical plugin JAR deployments are not allowed");
                }
                throw new IllegalStateException(
                        "Distinct plugin JARs have the same SHA-256 digest");
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Plugin JARs with matching digests could not be compared", e);
            }
        }
    }

    private static String contentAddressedSnapshotName(CatalogDigests.Digest digest) {
        return digest.value().substring("sha256:".length()) + ".jar";
    }

    private static void cleanupAfterConstructionFailure(
            URLClassLoader loader,
            SnapshotLease snapshotLease,
            Throwable failure
    ) {
        Throwable winner = failure;
        if (loader != null) {
            try {
                loader.close();
            } catch (Throwable cleanup) {
                winner = recordCleanupFailure(winner, cleanup);
            }
        }
        try {
            snapshotLease.closeAndDelete();
        } catch (Throwable cleanup) {
            winner = recordCleanupFailure(winner, cleanup);
        }
        if (winner != failure && winner instanceof Error error) {
            throw error;
        }
    }

    private static Throwable closeAfterFailure(
            AutoCloseable closeable,
            Throwable failure
    ) {
        if (closeable == null) {
            return failure;
        }
        try {
            closeable.close();
        } catch (Throwable cleanup) {
            return recordCleanupFailure(failure, cleanup);
        }
        return failure;
    }

    private static Throwable deleteAfterFailure(Path directory, Throwable failure) {
        try {
            deleteSnapshotTree(directory);
        } catch (Throwable cleanup) {
            return recordCleanupFailure(failure, cleanup);
        }
        return failure;
    }

    /**
     * Retain the first fatal cleanup error while continuing every remaining
     * close/delete step. Ordinary failures are suppressed under that fatal
     * winner; a later fatal replaces an earlier ordinary failure.
     */
    static Throwable recordCleanupFailure(Throwable current, Throwable next) {
        return LifecycleFailures.merge(current, next);
    }

    private static void deleteSnapshotPayload(Path directory, Path leaseFile) throws IOException {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        IOException failure = null;
        int entries = 0;
        List<Path> payload = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) {
                if (++entries > MAX_PLUGIN_JARS + 1) {
                    throw new IOException("Plugin snapshot contains too many entries");
                }
                if (path.equals(leaseFile)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                        || !fileName.matches("(?:[0-9]{5}|[0-9a-f]{64})\\.jar")) {
                    throw new IOException("Plugin snapshot has an invalid flat-directory shape");
                }
                payload.add(path);
            }
        }
        // Validate the complete bounded shape before deleting anything. An
        // orphan that acquired an unexpected child is skipped intact.
        for (Path path : payload) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void deleteSnapshotTree(Path snapshotDirectory) throws IOException {
        if (snapshotDirectory == null
                || !Files.exists(snapshotDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path leaseFile = snapshotDirectory.resolve(SNAPSHOT_LEASE_FILE);
        deleteSnapshotPayload(snapshotDirectory, leaseFile);
        if (Files.exists(leaseFile, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes leaseAttributes = Files.readAttributes(
                    leaseFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!leaseAttributes.isRegularFile() || leaseAttributes.isSymbolicLink()) {
                throw new IOException("Plugin snapshot lease has an invalid shape");
            }
        }
        IOException failure = null;
        try {
            Files.deleteIfExists(leaseFile);
        } catch (IOException cleanup) {
            failure = cleanup;
        }
        try {
            Files.deleteIfExists(snapshotDirectory);
        } catch (IOException cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record DirectoryIdentity(Object fileKey, FileTime lastModifiedTime) {
    }

    private record SourceArtifact(Path path, SourceIdentity identity) {
    }

    private record SourceIdentity(Object fileKey, long size, FileTime lastModifiedTime) {
    }

    private record DigestedArtifact(
            Path path,
            String sourceName,
            String sourcePath,
            CatalogDigests.Digest digest
    ) {
    }

    @FunctionalInterface
    interface SnapshotCleanupCheckpoint {
        void afterLeaseAcquired(Path directory, Path leaseFile) throws IOException;
    }

    private static final class SnapshotLease {
        private final Path directory;
        private final Path leaseFile;
        private final FileChannel leaseChannel;
        private final FileLock leaseLock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SnapshotLease(
                Path directory,
                Path leaseFile,
                FileChannel leaseChannel,
                FileLock leaseLock
        ) {
            this.directory = directory;
            this.leaseFile = leaseFile;
            this.leaseChannel = leaseChannel;
            this.leaseLock = leaseLock;
        }

        private Path directory() {
            return directory;
        }

        private void closeAndDelete() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Throwable failure = null;
            try {
                deleteSnapshotPayload(directory, leaseFile);
            } catch (Throwable cleanup) {
                failure = cleanup;
            }
            try {
                leaseLock.release();
            } catch (Throwable cleanup) {
                failure = recordCleanupFailure(failure, cleanup);
            }
            try {
                leaseChannel.close();
            } catch (Throwable cleanup) {
                failure = recordCleanupFailure(failure, cleanup);
            }
            try {
                deleteSnapshotTree(directory);
            } catch (Throwable cleanup) {
                failure = recordCleanupFailure(failure, cleanup);
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure != null) {
                throw new IllegalStateException("Plugin snapshot cleanup failed", failure);
            }
        }
    }

    private static final class DirectoryLoaderOwnership implements AutoCloseable {
        private final SnapshotLease snapshotLease;
        private URLClassLoader loader;
        private boolean installed;
        private boolean closed;

        private DirectoryLoaderOwnership(SnapshotLease snapshotLease) {
            this.snapshotLease = snapshotLease;
        }

        private synchronized void install(URLClassLoader selectedLoader) {
            Objects.requireNonNull(selectedLoader, "selectedLoader");
            if (closed) {
                throw new IllegalStateException("Plugin loader ownership is already closed");
            }
            if (installed) {
                throw new IllegalStateException("Executable plugin loader is already installed");
            }
            loader = selectedLoader;
            installed = true;
        }

        @Override
        public void close() throws Exception {
            URLClassLoader selectedLoader;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                selectedLoader = loader;
            }
            Throwable failure = null;
            if (selectedLoader != null) {
                try {
                    selectedLoader.close();
                } catch (Throwable loaderFailure) {
                    failure = loaderFailure;
                }
            }
            try {
                snapshotLease.closeAndDelete();
            } catch (Throwable cleanupFailure) {
                failure = recordCleanupFailure(failure, cleanupFailure);
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure != null) {
                throw new IllegalStateException("Plugin loader ownership cleanup failed", failure);
            }
        }
    }
}
