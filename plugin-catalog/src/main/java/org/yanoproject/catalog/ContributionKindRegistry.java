package org.yanoproject.catalog;

import org.yanoproject.api.plugin.PluginTrustTier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The contribution kinds one catalog build or inspection accepts.
 *
 * <p>A registry holds the four built-in Yano kinds plus the kinds declared by
 * descriptor resources {@code META-INF/yano/contribution-kinds/<owner>.json}.
 * Descriptors are trusted by origin: they are read only from host artifacts,
 * which are the artifacts of the host class path. A plugin artifact that is
 * not a host artifact must not carry a descriptor (see
 * {@link PluginArtifactScanner}).</p>
 *
 * <p>Descriptor schema v1:</p>
 * <pre>{@code
 * {"schemaVersion": 1,
 *  "apiPackages": ["org.example.api"],
 *  "kinds": [{"key": "example-kind", "serviceInterface": "org.example.api.ExampleProvider",
 *             "manifestRequired": true, "trustTier": "PRIVILEGED_LOCAL",
 *             "maxPerBundle": 1, "nameIsBundleId": false, "forbiddenNameChars": ".",
 *             "reservedSystemNames": ["system"], "operationsTracked": false}]}
 * }</pre>
 * <p>{@code apiPackages} are API packages a plugin artifact must not package,
 * like the built-in {@code org.yanoproject.api}. The kind fields after
 * {@code trustTier} are optional.</p>
 */
public final class ContributionKindRegistry {
    /** Directory containing owner-qualified contribution-kind descriptors. */
    public static final String RESOURCE_DIRECTORY = "META-INF/yano/contribution-kinds/";
    /** File suffix required for contribution-kind descriptors. */
    public static final String RESOURCE_SUFFIX = ".json";
    /** Maximum encoded descriptor size. */
    public static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    /** Maximum number of kinds one registry accepts. */
    public static final int MAX_KINDS = 256;

    private static final int SCHEMA_VERSION = 1;
    private static final String BUILT_IN_API_PREFIX = "org/yanoproject/api/";
    private static final List<ContributionKind> BUILT_INS = List.of(
            ContributionKind.NODE_PLUGIN,
            ContributionKind.UTXO_INDEX_CONTRIBUTOR,
            ContributionKind.HEALTH,
            ContributionKind.METRICS);
    private static final ContributionKindRegistry BUILT_IN_REGISTRY =
            new ContributionKindRegistry(List.of(), Set.of(), Set.of());

    private final List<ContributionKind> orderedKinds;
    private final Map<String, ContributionKind> byKey;
    private final Map<String, ContributionKind> byInterface;
    private final List<String> apiClassPrefixes;
    private final Set<Path> hostArtifacts;

    private ContributionKindRegistry(
            List<ContributionKind> descriptorKinds,
            Set<String> descriptorApiPrefixes,
            Set<Path> hostArtifacts
    ) {
        Map<String, ContributionKind> keys = new LinkedHashMap<>();
        Map<String, ContributionKind> interfaces = new HashMap<>();
        List<ContributionKind> ordered = new ArrayList<>(BUILT_INS);
        descriptorKinds.stream()
                .sorted(Comparator.comparing(ContributionKind::manifestKey))
                .forEach(ordered::add);
        if (ordered.size() > MAX_KINDS) {
            throw new PluginCatalogException("Contribution-kind descriptors declare more than "
                    + MAX_KINDS + " kinds");
        }
        for (ContributionKind kind : ordered) {
            ContributionKind previous = keys.putIfAbsent(kind.manifestKey(), kind);
            if (previous != null) {
                throw new PluginCatalogException("Contribution kind '" + kind.manifestKey()
                        + "' is defined more than once"
                        + (BUILT_INS.contains(kind) ? " and redefines a built-in kind" : ""));
            }
            previous = interfaces.putIfAbsent(kind.serviceInterface(), kind);
            if (previous != null) {
                throw new PluginCatalogException("Service interface '" + kind.serviceInterface()
                        + "' is registered under both '" + previous.manifestKey()
                        + "' and '" + kind.manifestKey() + "'");
            }
        }
        this.byKey = Map.copyOf(keys);
        this.byInterface = Map.copyOf(interfaces);
        List<String> prefixes = new ArrayList<>();
        prefixes.add(BUILT_IN_API_PREFIX);
        prefixes.addAll(new TreeSet<>(descriptorApiPrefixes));
        this.apiClassPrefixes = List.copyOf(prefixes);
        this.hostArtifacts = Set.copyOf(hostArtifacts);
        this.orderedKinds = List.copyOf(ordered);
    }

    /**
     * Returns the registry of the four built-in kinds, with no host artifacts.
     *
     * @return built-in registry
     */
    public static ContributionKindRegistry builtIns() {
        return BUILT_IN_REGISTRY;
    }

    /**
     * Builds a registry from the built-in kinds and every descriptor carried by
     * the given host artifacts (JAR files or exploded artifact directories).
     *
     * @param hostArtifacts the artifacts of the trusted host class path
     * @return registry whose host artifacts are exactly {@code hostArtifacts}
     * @throws IOException if an artifact cannot be read
     * @throws PluginCatalogException if a descriptor is invalid or conflicts
     */
    public static ContributionKindRegistry fromHostArtifacts(Collection<Path> hostArtifacts)
            throws IOException {
        Objects.requireNonNull(hostArtifacts, "hostArtifacts");
        Map<String, Path> owners = new TreeMap<>();
        List<ContributionKind> kinds = new ArrayList<>();
        Set<String> apiPrefixes = new HashSet<>();
        Set<Path> canonical = new HashSet<>();
        for (Path artifact : hostArtifacts) {
            Path real = Objects.requireNonNull(artifact, "hostArtifacts must not contain null")
                    .toRealPath();
            if (!canonical.add(real)) {
                continue;
            }
            for (Map.Entry<String, byte[]> descriptor : readDescriptors(real).entrySet()) {
                String owner = owner(descriptor.getKey());
                Path previous = owners.putIfAbsent(owner, real);
                if (previous != null) {
                    throw invalid(descriptor.getKey(), "owner is declared by more than one host artifact");
                }
                Descriptor parsed = parse(descriptor.getKey(), descriptor.getValue());
                kinds.addAll(parsed.kinds());
                apiPrefixes.addAll(parsed.apiClassPrefixes());
            }
        }
        return new ContributionKindRegistry(kinds, apiPrefixes, canonical);
    }

    /**
     * Returns every accepted kind: the built-ins first, then descriptor kinds by key.
     *
     * @return immutable ordered kinds
     */
    public List<ContributionKind> kinds() {
        return orderedKinds;
    }

    /**
     * Resolves a strict manifest kind key.
     *
     * @param manifestKey manifest discriminator
     * @return corresponding contribution kind
     * @throws IllegalArgumentException if the key is unsupported
     */
    public ContributionKind fromManifestKey(String manifestKey) {
        ContributionKind kind = manifestKey == null ? null : byKey.get(manifestKey);
        if (kind == null) {
            throw new IllegalArgumentException("contribution kind is not supported");
        }
        return kind;
    }

    /**
     * Resolves the kind whose ServiceLoader SPI has the given binary name.
     *
     * @param serviceInterface service interface binary name
     * @return the kind, or empty when the interface is not a contribution SPI
     */
    public Optional<ContributionKind> forServiceInterface(String serviceInterface) {
        return Optional.ofNullable(byInterface.get(serviceInterface));
    }

    /**
     * Whether {@code artifact} is one of this registry's trusted host artifacts.
     *
     * @param artifact JAR file or exploded artifact directory
     * @return true when the artifact's real path is a host artifact
     * @throws IOException if the path cannot be resolved
     */
    public boolean isHostArtifact(Path artifact) throws IOException {
        return !hostArtifacts.isEmpty() && hostArtifacts.contains(artifact.toRealPath());
    }

    boolean isApiClass(String classPath) {
        return apiClassPrefixes.stream().anyMatch(classPath::startsWith);
    }

    static boolean isDescriptorPath(String path) {
        if (!path.startsWith(RESOURCE_DIRECTORY) || !path.endsWith(RESOURCE_SUFFIX)) {
            return false;
        }
        String filename = path.substring(RESOURCE_DIRECTORY.length());
        return filename.indexOf('/') < 0 && filename.indexOf('\\') < 0;
    }

    private static Map<String, byte[]> readDescriptors(Path artifact) throws IOException {
        Map<String, byte[]> descriptors = new TreeMap<>();
        if (Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS)) {
            Path directory = artifact.resolve(RESOURCE_DIRECTORY);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return descriptors;
            }
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    String path = RESOURCE_DIRECTORY + file.getFileName();
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && isDescriptorPath(path)) {
                        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                            descriptors.put(path, readBounded(path, input));
                        }
                    }
                }
            }
            return descriptors;
        }
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            for (JarEntry entry : jar.stream().toList()) {
                if (!entry.isDirectory() && isDescriptorPath(entry.getName())) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        descriptors.put(entry.getName(), readBounded(entry.getName(), input));
                    }
                }
            }
        }
        return descriptors;
    }

    private static byte[] readBounded(String path, InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
        if (bytes.length > MAX_DESCRIPTOR_BYTES) {
            throw invalid(path, "descriptor exceeds " + MAX_DESCRIPTOR_BYTES + " bytes");
        }
        return bytes;
    }

    private static String owner(String path) {
        String owner = path.substring(RESOURCE_DIRECTORY.length(), path.length() - RESOURCE_SUFFIX.length());
        try {
            return CatalogValidation.bundleId(owner, "owner");
        } catch (IllegalArgumentException e) {
            throw invalid(path, "filename must be a reverse-DNS owner id", e);
        }
    }

    private static Descriptor parse(String path, byte[] bytes) {
        RawDescriptor raw;
        try {
            raw = BundleManifestParser.MAPPER.readValue(bytes, RawDescriptor.class);
        } catch (IOException e) {
            throw invalid(path, "malformed descriptor JSON or unknown field", e);
        }
        try {
            if (raw == null || raw.schemaVersion == null || raw.schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("schemaVersion must equal 1");
            }
            List<String> prefixes = new ArrayList<>();
            for (String apiPackage : raw.apiPackages == null ? List.<String>of() : raw.apiPackages) {
                prefixes.add(CatalogValidation.providerClass(apiPackage, "apiPackages[]")
                        .replace('.', '/') + "/");
            }
            if (raw.kinds == null) {
                throw new IllegalArgumentException("kinds must be present");
            }
            List<ContributionKind> kinds = new ArrayList<>();
            for (RawKind kind : raw.kinds) {
                if (kind == null || kind.manifestRequired == null) {
                    throw new IllegalArgumentException("kinds[].manifestRequired must be present");
                }
                kinds.add(new ContributionKind(
                        kind.key,
                        kind.serviceInterface,
                        kind.manifestRequired,
                        kind.trustTier,
                        kind.maxPerBundle == null ? CatalogValidation.MAX_CONTRIBUTIONS : kind.maxPerBundle,
                        Boolean.TRUE.equals(kind.nameIsBundleId),
                        kind.forbiddenNameChars == null ? "" : kind.forbiddenNameChars,
                        kind.reservedSystemNames == null ? Set.of() : Set.copyOf(kind.reservedSystemNames),
                        Boolean.TRUE.equals(kind.operationsTracked)));
            }
            return new Descriptor(kinds, prefixes);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid(path, String.valueOf(e.getMessage()), e);
        }
    }

    private static PluginCatalogException invalid(String path, String reason) {
        return new PluginCatalogException("Invalid contribution-kind descriptor " + path + ": " + reason);
    }

    private static PluginCatalogException invalid(String path, String reason, Throwable cause) {
        return new PluginCatalogException(
                "Invalid contribution-kind descriptor " + path + ": " + reason, cause);
    }

    private record Descriptor(List<ContributionKind> kinds, List<String> apiClassPrefixes) {
    }

    private record RawDescriptor(Integer schemaVersion, List<String> apiPackages, List<RawKind> kinds) {
    }

    private record RawKind(
            String key,
            String serviceInterface,
            Boolean manifestRequired,
            PluginTrustTier trustTier,
            Integer maxPerBundle,
            Boolean nameIsBundleId,
            String forbiddenNameChars,
            List<String> reservedSystemNames,
            Boolean operationsTracked
    ) {
    }
}
