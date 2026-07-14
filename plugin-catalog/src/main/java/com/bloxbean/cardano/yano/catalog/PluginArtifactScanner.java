package com.bloxbean.cardano.yano.catalog;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Inspects one plugin JAR or exploded artifact without loading provider code.
 *
 * <p>The scanner treats the artifact as the correlation boundary: a
 * manifested artifact must declare exactly the supported ServiceLoader
 * entries it contains. An unmanifested artifact is represented by one legacy
 * entry per supported service provider.</p>
 */
public final class PluginArtifactScanner {
    /** Maximum bytes accepted from one supported ServiceLoader file. */
    public static final int MAX_SERVICE_FILE_BYTES = 1024 * 1024;
    /** Maximum bytes inspected from a JAR manifest. */
    public static final int MAX_JAR_MANIFEST_BYTES = 1024 * 1024;
    /** Maximum filesystem or JAR entries inspected in one artifact. */
    public static final int MAX_ARTIFACT_ENTRIES = 100_000;
    /** Maximum characters accepted in one artifact entry name. */
    public static final int MAX_ENTRY_NAME_LENGTH = 4_096;
    /** Maximum aggregate entry-name characters retained while scanning. */
    public static final int MAX_TOTAL_ENTRY_NAME_CHARACTERS = 16 * 1024 * 1024;

    private static final String SERVICE_DIRECTORY = "META-INF/services/";
    private static final String API_CLASS_PREFIX = "com/bloxbean/cardano/yano/api/";
    private static final String MULTI_RELEASE_PREFIX = "META-INF/versions/";

    private final BundleManifestParser manifestParser;

    /** Creates a scanner using the strict schema-v1 manifest parser. */
    public PluginArtifactScanner() {
        this(new BundleManifestParser());
    }

    PluginArtifactScanner(BundleManifestParser manifestParser) {
        this.manifestParser = Objects.requireNonNull(manifestParser, "manifestParser");
    }

    /**
     * Scans exactly one regular JAR file or exploded artifact directory.
     *
     * @param artifact artifact to inspect without loading provider code
     * @return canonical partial index, or an empty index for an ordinary artifact
     * @throws IOException if the artifact cannot be read safely
     * @throws PluginCatalogException if plugin metadata or artifact correlation is invalid
     */
    public PluginIndex scan(Path artifact) throws IOException {
        return scan(artifact, false);
    }

    /**
     * Scans one artifact and optionally enforces a closed executable classpath
     * even when the artifact itself contains no plugin provider metadata. This
     * is used for every dependency assigned to a strict build-time bundle
     * closure; unrelated application dependencies remain outside that policy.
     */
    PluginIndex scan(Path artifact, boolean requireClosedClassPath) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        ArtifactContents probe = inspect(artifact, requireClosedClassPath);
        boolean pluginArtifact = probe.manifest() != null
                || hasServiceProviders(probe.services());
        rejectManifestClassPath(probe, pluginArtifact || requireClosedClassPath);
        if (!pluginArtifact) {
            return PluginIndex.empty();
        }

        CatalogDigests.Digest before = CatalogDigests.artifact(artifact);
        ArtifactContents contents = inspect(artifact, requireClosedClassPath);
        rejectManifestClassPath(contents, true);
        CatalogDigests.Digest after = CatalogDigests.artifact(artifact);
        if (!before.equals(after)) {
            throw invalid("artifact changed while it was being indexed");
        }
        return correlate(contents, after);
    }

    private ArtifactContents inspect(Path artifact, boolean inspectOrdinaryManifest)
            throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                artifact, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) {
            throw new IOException("Plugin artifact must not be a symbolic link");
        }
        if (attributes.isRegularFile()) {
            return scanJar(artifact, inspectOrdinaryManifest);
        } else if (attributes.isDirectory()) {
            return scanDirectory(artifact, inspectOrdinaryManifest);
        } else {
            throw new IOException("Plugin artifact is neither a regular file nor directory");
        }
    }

    private ArtifactContents scanJar(Path artifact, boolean inspectOrdinaryManifest)
            throws IOException {
        Map<String, JarEntry> entries = new HashMap<>();
        Set<String> fileEntries = new HashSet<>();
        List<String> manifestPaths = new ArrayList<>();
        List<JarEntry> jarManifestEntries = new ArrayList<>();
        Map<ContributionKind, byte[]> serviceFiles = new EnumMap<>(ContributionKind.class);
        String apiClass = null;
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            var enumeration = jar.entries();
            int entryCount = 0;
            long totalEntryNameCharacters = 0;
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                String name = entry.getName();
                if (++entryCount > MAX_ARTIFACT_ENTRIES) {
                    throw invalid("artifact contains more than " + MAX_ARTIFACT_ENTRIES + " entries");
                }
                if (name.length() > MAX_ENTRY_NAME_LENGTH) {
                    throw invalid("artifact contains an entry name longer than "
                            + MAX_ENTRY_NAME_LENGTH + " characters");
                }
                totalEntryNameCharacters = addEntryNameCharacters(
                        totalEntryNameCharacters, name.length());
                if (entries.putIfAbsent(name, entry) != null) {
                    throw invalid("artifact contains duplicate JAR entry '" + safeEntry(name) + "'");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                fileEntries.add(name);
                if (isJarManifestPath(name)) {
                    jarManifestEntries.add(entry);
                }
                rejectNestedAggregateIndex(name);
                if (isApiClass(name) && (apiClass == null || name.compareTo(apiClass) < 0)) {
                    apiClass = name;
                }
                if (isQualifiedManifestPath(name)) {
                    manifestPaths.add(name);
                }
                ContributionKind kind = serviceKind(name);
                if (kind != null) {
                    if (serviceFiles.containsKey(kind)) {
                        throw invalid("artifact contains duplicate service file for " + kind.manifestKey());
                    }
                    try (InputStream input = jar.getInputStream(entry)) {
                        serviceFiles.put(kind, readBounded(input, MAX_SERVICE_FILE_BYTES, "service file"));
                    }
                }
            }
            boolean manifestClassPath = (inspectOrdinaryManifest
                    || !manifestPaths.isEmpty() || !serviceFiles.isEmpty())
                    && manifestDeclaresClassPath(jar, jarManifestEntries);
            BundleManifest manifest = readJarManifest(jar, entries, manifestPaths);
            return new ArtifactContents(
                    manifest,
                    parseServices(serviceFiles, providerLimit(manifest)),
                    apiClass,
                    Set.copyOf(fileEntries),
                    manifestClassPath);
        }
    }

    private BundleManifest readJarManifest(
            JarFile jar,
            Map<String, JarEntry> entries,
            List<String> manifestPaths
    ) throws IOException {
        validateManifestCount(manifestPaths);
        if (manifestPaths.isEmpty()) {
            return null;
        }
        String path = manifestPaths.getFirst();
        try (InputStream input = jar.getInputStream(entries.get(path))) {
            return manifestParser.parse(path, input);
        }
    }

    private ArtifactContents scanDirectory(Path artifact, boolean inspectOrdinaryManifest)
            throws IOException {
        List<Path> allPaths;
        try (var stream = Files.walk(artifact)) {
            allPaths = stream.limit(MAX_ARTIFACT_ENTRIES + 2L).toList();
        }
        if (allPaths.size() > MAX_ARTIFACT_ENTRIES + 1) {
            throw invalid("artifact contains more than " + MAX_ARTIFACT_ENTRIES + " entries");
        }
        allPaths = allPaths.stream()
                .sorted(Comparator.comparing(path -> relativeName(artifact, path)))
                .toList();
        List<String> manifestPaths = new ArrayList<>();
        List<Path> jarManifestFiles = new ArrayList<>();
        Set<String> fileEntries = new HashSet<>();
        Map<ContributionKind, byte[]> serviceFiles = new EnumMap<>(ContributionKind.class);
        String apiClass = null;
        long totalEntryNameCharacters = 0;
        for (Path path : allPaths) {
            if (path.equals(artifact)) {
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw invalid("exploded artifact must not contain symbolic links");
            }
            if (!attributes.isRegularFile()) {
                continue;
            }
            String name = relativeName(artifact, path);
            if (name.length() > MAX_ENTRY_NAME_LENGTH) {
                throw invalid("artifact contains an entry name longer than "
                        + MAX_ENTRY_NAME_LENGTH + " characters");
            }
            totalEntryNameCharacters = addEntryNameCharacters(
                    totalEntryNameCharacters, name.length());
            fileEntries.add(name);
            if (isJarManifestPath(name)) {
                jarManifestFiles.add(path);
            }
            rejectNestedAggregateIndex(name);
            if (isApiClass(name) && (apiClass == null || name.compareTo(apiClass) < 0)) {
                apiClass = name;
            }
            if (isQualifiedManifestPath(name)) {
                manifestPaths.add(name);
            }
            ContributionKind kind = serviceKind(name);
            if (kind != null) {
                serviceFiles.put(kind, readBounded(path, MAX_SERVICE_FILE_BYTES, "service file"));
            }
        }
        boolean manifestClassPath = (inspectOrdinaryManifest
                || !manifestPaths.isEmpty() || !serviceFiles.isEmpty())
                && manifestDeclaresClassPath(jarManifestFiles);
        validateManifestCount(manifestPaths);
        BundleManifest manifest = null;
        if (!manifestPaths.isEmpty()) {
            String manifestPath = manifestPaths.getFirst();
            try (InputStream input = Files.newInputStream(
                    artifact.resolve(manifestPath), LinkOption.NOFOLLOW_LINKS)) {
                manifest = manifestParser.parse(manifestPath, input);
            }
        }
        return new ArtifactContents(
                manifest,
                parseServices(serviceFiles, providerLimit(manifest)),
                apiClass,
                Set.copyOf(fileEntries),
                manifestClassPath);
    }

    private PluginIndex correlate(ArtifactContents contents, CatalogDigests.Digest digest) {
        boolean pluginArtifact = contents.manifest() != null || hasServiceProviders(contents.services());
        if (pluginArtifact && contents.apiClass() != null) {
            throw invalid("plugin artifact packages Yano API class '"
                    + safeEntry(contents.apiClass()) + "'");
        }
        Set<ServiceDeclaration> registered = new HashSet<>();
        contents.services().forEach((kind, providers) -> providers.forEach(
                provider -> registered.add(new ServiceDeclaration(kind, provider))));
        if (contents.manifest() == null) {
            validateProviderClasses(registered, contents.fileEntries());
            List<IndexedLegacyProvider> providers = contents.services().entrySet().stream()
                    .flatMap(entry -> entry.getValue().stream()
                            .map(provider -> new IndexedLegacyProvider(
                                    entry.getKey(), provider, digest.value(), digest.mode())))
                    .toList();
            return new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION, List.of(), providers);
        }

        Set<ServiceDeclaration> declared = new HashSet<>();
        for (BundleContribution contribution : contents.manifest().contributions()) {
            declared.add(new ServiceDeclaration(contribution.kind(), contribution.provider()));
        }

        if (!declared.equals(registered)) {
            List<String> missing = declared.stream()
                    .filter(declaration -> !registered.contains(declaration))
                    .map(ServiceDeclaration::display)
                    .sorted()
                    .toList();
            List<String> undeclared = registered.stream()
                    .filter(declaration -> !declared.contains(declaration))
                    .map(ServiceDeclaration::display)
                    .sorted()
                    .toList();
            throw invalid("manifest and supported ServiceLoader entries differ; missing="
                    + missing + ", undeclared=" + undeclared);
        }
        validateProviderClasses(registered, contents.fileEntries());

        IndexedBundle bundle = new IndexedBundle(contents.manifest(), digest.value(), digest.mode());
        return new PluginIndex(PluginIndex.CURRENT_SCHEMA_VERSION, List.of(bundle), List.of());
    }

    private static void validateProviderClasses(
            Set<ServiceDeclaration> registered,
            Set<String> fileEntries
    ) {
        for (ServiceDeclaration declaration : registered.stream()
                .sorted(Comparator.comparing(ServiceDeclaration::display))
                .toList()) {
            String classPath = declaration.provider().replace('.', '/') + ".class";
            if (!fileEntries.contains(classPath)) {
                throw invalid("supported service provider '" + declaration.display()
                        + "' has no base class entry in the same artifact");
            }
            boolean multiReleaseOverride = fileEntries.stream()
                    .anyMatch(entry -> isMultiReleaseOverride(entry, classPath));
            if (multiReleaseOverride) {
                throw invalid("supported service provider '" + declaration.display()
                        + "' must not use a multi-release class override");
            }
        }
    }

    private static boolean hasServiceProviders(Map<ContributionKind, List<String>> services) {
        return services.values().stream().anyMatch(providers -> !providers.isEmpty());
    }

    private static int providerLimit(BundleManifest manifest) {
        return manifest == null
                ? PluginIndex.MAX_LEGACY_PROVIDERS
                : CatalogValidation.MAX_CONTRIBUTIONS;
    }

    private static Map<ContributionKind, List<String>> parseServices(
            Map<ContributionKind, byte[]> serviceFiles,
            int providerLimit
    ) {
        Map<ContributionKind, List<String>> services = new LinkedHashMap<>();
        int total = 0;
        for (ContributionKind kind : ContributionKind.values()) {
            byte[] bytes = serviceFiles.get(kind);
            if (bytes != null) {
                List<String> providers = parseServiceFile(
                        kind, bytes, providerLimit - total, providerLimit);
                total += providers.size();
                services.put(kind, providers);
            }
        }
        return Map.copyOf(services);
    }

    private static List<String> parseServiceFile(
            ContributionKind kind,
            byte[] bytes,
            int remainingLimit,
            int totalLimit
    ) {
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid("service file for " + kind.manifestKey() + " must be valid UTF-8", e);
        }
        List<String> providers = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            int lineNumber = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                lineNumber++;
                int comment = line.indexOf('#');
                String candidate = (comment >= 0 ? line.substring(0, comment) : line).trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                try {
                    candidate = CatalogNames.providerClass(candidate);
                } catch (IllegalArgumentException e) {
                    throw invalid("service file for " + kind.manifestKey()
                            + " has invalid provider at line " + lineNumber, e);
                }
                if (!unique.add(candidate)) {
                    throw invalid("service file for " + kind.manifestKey()
                            + " repeats provider '" + candidate + "'");
                }
                if (providers.size() >= remainingLimit) {
                    throw invalid("artifact contains more than " + totalLimit
                            + " supported service providers");
                }
                providers.add(candidate);
            }
        } catch (IOException impossible) {
            throw new IllegalStateException("StringReader failed", impossible);
        }
        return List.copyOf(providers);
    }

    private static byte[] readBounded(Path path, int limit, String description) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return readBounded(input, limit, description);
        }
    }

    private static boolean manifestDeclaresClassPath(
            JarFile jar,
            List<JarEntry> manifestEntries
    ) throws IOException {
        if (manifestEntries.size() > 1) {
            throw invalid("artifact contains more than one case-insensitive JAR manifest");
        }
        if (manifestEntries.isEmpty()) {
            return false;
        }
        try (InputStream input = jar.getInputStream(manifestEntries.getFirst())) {
            return manifestDeclaresClassPath(readBounded(
                    input, MAX_JAR_MANIFEST_BYTES, "JAR manifest"));
        }
    }

    private static boolean manifestDeclaresClassPath(List<Path> manifestFiles)
            throws IOException {
        if (manifestFiles.size() > 1) {
            throw invalid("artifact contains more than one case-insensitive JAR manifest");
        }
        if (manifestFiles.isEmpty()) {
            return false;
        }
        return manifestDeclaresClassPath(readBounded(
                manifestFiles.getFirst(), MAX_JAR_MANIFEST_BYTES, "JAR manifest"));
    }

    private static boolean manifestDeclaresClassPath(byte[] manifestBytes) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(manifestBytes));
        return manifest.getMainAttributes().getValue(
                java.util.jar.Attributes.Name.CLASS_PATH) != null;
    }

    private static void rejectManifestClassPath(
            ArtifactContents contents,
            boolean closedClassPathRequired
    ) {
        if (closedClassPathRequired && contents.manifestClassPath()) {
            throw invalid("JAR manifest Class-Path is unsupported; "
                    + "all executable dependencies must be explicit catalog artifacts");
        }
    }

    private static long addEntryNameCharacters(long current, int addition) {
        long total = current + addition;
        if (total > MAX_TOTAL_ENTRY_NAME_CHARACTERS) {
            throw invalid("artifact entry names exceed "
                    + MAX_TOTAL_ENTRY_NAME_CHARACTERS + " total characters");
        }
        return total;
    }

    private static byte[] readBounded(InputStream input, int limit, String description) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int read; (read = input.read(buffer)) >= 0; ) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > limit) {
                throw invalid(description + " exceeds " + limit + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static ContributionKind serviceKind(String path) {
        if (!path.startsWith(SERVICE_DIRECTORY)) {
            return null;
        }
        String serviceName = path.substring(SERVICE_DIRECTORY.length());
        for (ContributionKind kind : ContributionKind.values()) {
            if (kind.serviceType().getName().equals(serviceName)) {
                return kind;
            }
        }
        return null;
    }

    private static boolean isJarManifestPath(String path) {
        return JarFile.MANIFEST_NAME.equalsIgnoreCase(path);
    }

    private static boolean isQualifiedManifestPath(String path) {
        if (!path.startsWith(BundleManifestParser.RESOURCE_DIRECTORY)
                || !path.endsWith(BundleManifestParser.RESOURCE_SUFFIX)) {
            return false;
        }
        String filename = path.substring(BundleManifestParser.RESOURCE_DIRECTORY.length());
        return !filename.isEmpty() && filename.indexOf('/') < 0 && filename.indexOf('\\') < 0;
    }

    private static void rejectNestedAggregateIndex(String path) {
        if (PluginIndex.RESOURCE_PATH.equals(path)
                || isMultiReleaseOverride(path, PluginIndex.RESOURCE_PATH)) {
            throw invalid("input artifact already contains aggregate plugin index '"
                    + PluginIndex.RESOURCE_PATH + "'");
        }
    }

    private static void validateManifestCount(List<String> manifestPaths) {
        if (manifestPaths.size() > 1) {
            throw invalid("artifact contains more than one qualified plugin manifest: "
                    + manifestPaths.stream().sorted().map(PluginArtifactScanner::safeEntry).toList());
        }
    }

    private static boolean isApiClass(String path) {
        if (!path.endsWith(".class")) {
            return false;
        }
        if (path.startsWith(API_CLASS_PREFIX)) {
            return true;
        }
        if (!path.startsWith(MULTI_RELEASE_PREFIX)) {
            return false;
        }
        int versionEnd = path.indexOf('/', MULTI_RELEASE_PREFIX.length());
        if (versionEnd < 0) {
            return false;
        }
        String version = path.substring(MULTI_RELEASE_PREFIX.length(), versionEnd);
        if (version.isEmpty() || !version.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return path.substring(versionEnd + 1).startsWith(API_CLASS_PREFIX);
    }

    private static boolean isMultiReleaseOverride(String entry, String baseClassPath) {
        if (!entry.startsWith(MULTI_RELEASE_PREFIX) || !entry.endsWith("/" + baseClassPath)) {
            return false;
        }
        int versionEnd = entry.indexOf('/', MULTI_RELEASE_PREFIX.length());
        if (versionEnd < 0) {
            return false;
        }
        String version = entry.substring(MULTI_RELEASE_PREFIX.length(), versionEnd);
        return !version.isEmpty()
                && version.chars().allMatch(Character::isDigit)
                && entry.substring(versionEnd + 1).equals(baseClassPath);
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static String safeEntry(String entry) {
        String value = entry == null ? "<unknown>" : entry.replaceAll("[^A-Za-z0-9_.$/+-]", "?");
        return value.length() <= 512 ? value : value.substring(0, 512) + "...";
    }

    private static PluginCatalogException invalid(String message) {
        return new PluginCatalogException("Invalid plugin artifact: " + message);
    }

    private static PluginCatalogException invalid(String message, Throwable cause) {
        return new PluginCatalogException("Invalid plugin artifact: " + message, cause);
    }

    private record ArtifactContents(
            BundleManifest manifest,
            Map<ContributionKind, List<String>> services,
            String apiClass,
            Set<String> fileEntries,
            boolean manifestClassPath
    ) {
    }

    private record ServiceDeclaration(ContributionKind kind, String provider) {
        private String display() {
            return kind.manifestKey() + ":" + provider;
        }
    }
}
