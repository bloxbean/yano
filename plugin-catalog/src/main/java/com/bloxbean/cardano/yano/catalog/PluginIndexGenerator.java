package com.bloxbean.cardano.yano.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Build-time API and command-line entry point for aggregate plugin indexes. */
public final class PluginIndexGenerator {
    private final PluginArtifactScanner scanner;
    private final PluginIndexCodec codec;

    /** Creates a generator using the strict scanner and canonical codec. */
    public PluginIndexGenerator() {
        this(new PluginArtifactScanner(), new PluginIndexCodec());
    }

    PluginIndexGenerator(PluginArtifactScanner scanner, PluginIndexCodec codec) {
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * Scan and merge artifacts into one canonical model.
     *
     * <p>Input order cannot affect the returned index. Repeating the same
     * canonical artifact path is rejected rather than silently scanning it
     * twice.</p>
     *
     * @param artifacts JAR files and exploded artifact directories to inspect
     * @return merged canonical index
     * @throws IOException if an input cannot be inspected safely
     * @throws PluginCatalogException if inputs conflict or contain invalid metadata
     */
    public PluginIndex generate(Collection<Path> artifacts) throws IOException {
        Objects.requireNonNull(artifacts, "artifacts");
        return generateCanonical(canonicalArtifacts(artifacts));
    }

    private PluginIndex generateCanonical(List<Path> canonicalArtifacts) throws IOException {
        return scanCanonical(canonicalArtifacts).index();
    }

    private ScanResult scanCanonical(List<Path> canonicalArtifacts) throws IOException {
        return scanCanonical(canonicalArtifacts, Set.of());
    }

    private ScanResult scanCanonical(
            List<Path> canonicalArtifacts,
            Set<Path> closedClasspathArtifacts
    ) throws IOException {
        List<IndexedBundle> bundles = new ArrayList<>();
        List<IndexedLegacyProvider> legacyProviders = new ArrayList<>();
        Map<String, Path> bundleSources = new TreeMap<>();
        for (Path artifact : canonicalArtifacts) {
            PluginIndex partial = scanner.scan(
                    artifact, closedClasspathArtifacts.contains(artifact));
            bundles.addAll(partial.bundles());
            legacyProviders.addAll(partial.legacyProviders());
            partial.bundles().forEach(bundle -> bundleSources.put(
                    bundle.manifest().id(), artifact));
        }
        try {
            PluginIndex index = new PluginIndex(
                    PluginIndex.CURRENT_SCHEMA_VERSION, bundles, legacyProviders);
            return new ScanResult(index, Map.copyOf(bundleSources));
        } catch (IllegalArgumentException e) {
            throw new PluginCatalogException("Cannot merge plugin artifacts: " + e.getMessage(), e);
        }
    }

    /**
     * Scans a build-time classpath and records a strict executable closure for
     * every manifested bundle.
     *
     * <p>The map must contain exactly the discovered bundle ids. Each value
     * must include that bundle's manifest/provider artifact and all of its
     * executable dependencies, and every mapped path must also be present in
     * {@code artifacts}. This prevents a future thin provider from silently
     * falling back to thin-JAR-only evidence.</p>
     *
     * @param artifacts every resolved artifact on the application runtime classpath
     * @param bundleClosures exact bundle-id to root-plus-dependencies mappings
     * @return merged canonical index carrying per-bundle closure evidence
     * @throws IOException if an input cannot be inspected safely
     * @throws PluginCatalogException if inputs, mappings, or metadata are invalid
     */
    public PluginIndex generateArtifactClosures(
            Collection<Path> artifacts,
            Map<String, ? extends Collection<Path>> bundleClosures
    ) throws IOException {
        Objects.requireNonNull(artifacts, "artifacts");
        List<Path> canonicalArtifacts = canonicalArtifacts(artifacts);
        Map<String, List<Path>> canonicalClosures = canonicalClosures(
                bundleClosures, Set.copyOf(canonicalArtifacts));
        return generateArtifactClosuresCanonical(canonicalArtifacts, canonicalClosures);
    }

    private PluginIndex generateArtifactClosuresCanonical(
            List<Path> canonicalArtifacts,
            Map<String, List<Path>> bundleClosures
    ) throws IOException {
        Map<Path, CatalogDigests.Digest> before = artifactEvidence(canonicalArtifacts);
        Set<Path> closedClasspathArtifacts = bundleClosures.values().stream()
                .flatMap(Collection::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ScanResult scanned = scanCanonical(canonicalArtifacts, closedClasspathArtifacts);
        rejectLegacyBuildTimeProviders(scanned.index());
        Map<Path, CatalogDigests.Digest> after = artifactEvidence(canonicalArtifacts);
        if (!before.equals(after)) {
            throw new PluginCatalogException(
                    "Application runtime classpath changed during plugin index generation");
        }
        validateClosureKeys(scanned.index(), bundleClosures);
        List<IndexedBundle> bundles = new ArrayList<>(scanned.index().bundles().size());
        for (IndexedBundle bundle : scanned.index().bundles()) {
            String id = bundle.manifest().id();
            List<Path> closure = bundleClosures.get(id);
            Path root = scanned.bundleSources().get(id);
            if (!closure.contains(root)) {
                throw new PluginCatalogException("Artifact closure for bundle '" + id
                        + "' does not include its manifest/provider artifact");
            }
            CatalogDigests.Digest digest = CatalogDigests.artifactClosure(
                    closure.stream().map(after::get).toList());
            bundles.add(new IndexedBundle(bundle.manifest(), digest.value(), digest.mode()));
        }
        return new PluginIndex(scanned.index().schemaVersion(), bundles,
                scanned.index().legacyProviders());
    }

    private static Map<Path, CatalogDigests.Digest> artifactEvidence(List<Path> artifacts)
            throws IOException {
        Map<Path, CatalogDigests.Digest> evidence = new LinkedHashMap<>();
        for (Path artifact : artifacts) {
            evidence.put(artifact, CatalogDigests.artifact(artifact));
        }
        return Map.copyOf(evidence);
    }

    private static Map<String, List<Path>> canonicalClosures(
            Map<String, ? extends Collection<Path>> bundleClosures,
            Set<Path> classpathArtifacts
    ) throws IOException {
        Objects.requireNonNull(bundleClosures, "bundleClosures");
        Map<String, List<Path>> result = new TreeMap<>();
        for (Map.Entry<String, ? extends Collection<Path>> entry : bundleClosures.entrySet()) {
            String id = CatalogValidation.bundleId(entry.getKey(), "bundle closure id");
            List<Path> closure = canonicalArtifacts(Objects.requireNonNull(
                    entry.getValue(), "bundle closure artifacts"));
            for (Path artifact : closure) {
                if (!classpathArtifacts.contains(artifact)) {
                    throw new PluginCatalogException("Artifact closure for bundle '" + id
                            + "' references an artifact outside the indexed runtime classpath: "
                            + artifact.getFileName());
                }
            }
            result.put(id, closure);
        }
        return Map.copyOf(result);
    }

    private static void validateClosureKeys(
            PluginIndex index,
            Map<String, List<Path>> bundleClosures
    ) {
        Set<String> discovered = new TreeSet<>();
        index.bundles().forEach(bundle -> discovered.add(bundle.manifest().id()));
        Set<String> configured = new TreeSet<>(bundleClosures.keySet());
        Set<String> missing = new TreeSet<>(discovered);
        missing.removeAll(configured);
        Set<String> unknown = new TreeSet<>(configured);
        unknown.removeAll(discovered);
        if (!missing.isEmpty() || !unknown.isEmpty()) {
            throw new PluginCatalogException(
                    "Build-time bundle artifact-closure mapping differs from discovered bundles; "
                            + "missing=" + missing + ", unknown=" + unknown);
        }
    }

    private static void rejectLegacyBuildTimeProviders(PluginIndex index) {
        if (index.legacyProviders().isEmpty()) {
            return;
        }
        List<String> providers = index.legacyProviders().stream()
                .map(IndexedLegacyProvider::provider)
                .sorted()
                .limit(16)
                .toList();
        String suffix = index.legacyProviders().size() > providers.size() ? ", ..." : "";
        throw new PluginCatalogException(
                "Build-time artifact-closure evidence requires a manifest for every provider; "
                        + "add a bundle manifest or deploy a self-contained JVM directory bundle; "
                        + "legacyProviders=" + providers + suffix);
    }

    /**
     * Generates and encodes an index without closing {@code output}.
     *
     * @param artifacts JAR files and exploded artifact directories to inspect
     * @param output destination stream
     * @throws IOException if an input cannot be read or output cannot be written
     * @throws PluginCatalogException if inputs conflict or contain invalid metadata
     */
    public void write(Collection<Path> artifacts, OutputStream output) throws IOException {
        codec.write(generate(artifacts), output);
    }

    /**
     * Generates an index and atomically replaces {@code output} where supported.
     *
     * <p>The complete index is encoded into a sibling temporary file before
     * replacement. Filesystems without atomic moves use a final replacing
     * move; an existing output remains untouched if generation or encoding
     * fails.</p>
     *
     * @param artifacts JAR files and exploded artifact directories to inspect
     * @param output destination path, which must not be an input or symbolic link
     * @throws IOException if an input or output cannot be accessed
     * @throws PluginCatalogException if paths conflict or plugin metadata is invalid
     */
    public void write(Collection<Path> artifacts, Path output) throws IOException {
        write(artifacts, null, output);
    }

    /**
     * Generates a strict per-bundle artifact-closure index and atomically
     * replaces {@code output}.
     *
     * @param artifacts every resolved artifact on the application runtime classpath
     * @param bundleClosures exact bundle-id to root-plus-dependencies mappings
     * @param output destination path, which must not be an input or symbolic link
     * @throws IOException if an input or output cannot be accessed
     * @throws PluginCatalogException if paths, mappings, or plugin metadata are invalid
     */
    public void writeArtifactClosures(
            Collection<Path> artifacts,
            Map<String, ? extends Collection<Path>> bundleClosures,
            Path output
    ) throws IOException {
        write(artifacts, bundleClosures, output);
    }

    private void write(
            Collection<Path> artifacts,
            Map<String, ? extends Collection<Path>> bundleClosures,
            Path output
    )
            throws IOException {
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(output, "output");
        List<Path> canonicalArtifacts = canonicalArtifacts(artifacts);
        Path absoluteOutput = canonicalTarget(output);
        rejectOutputInsideArtifacts(canonicalArtifacts, absoluteOutput);
        PluginIndex index;
        if (bundleClosures == null) {
            index = generateCanonical(canonicalArtifacts);
        } else {
            Map<String, List<Path>> canonicalClosures = canonicalClosures(
                    bundleClosures, Set.copyOf(canonicalArtifacts));
            index = generateArtifactClosuresCanonical(canonicalArtifacts, canonicalClosures);
        }

        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String prefix = absoluteOutput.getFileName() == null
                ? "yano-plugin-index"
                : absoluteOutput.getFileName().toString();
        if (prefix.length() < 3) {
            prefix = "idx-" + prefix;
        }
        if (prefix.length() > 64) {
            prefix = prefix.substring(0, 64);
        }
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        boolean moved = false;
        try {
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                codec.write(index, stream);
            }
            try {
                Files.move(temporary, absoluteOutput,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void rejectOutputInsideArtifacts(List<Path> artifacts, Path output) {
        for (Path artifact : artifacts) {
            boolean conflicts = Files.isDirectory(artifact)
                    ? output.startsWith(artifact)
                    : output.equals(artifact);
            if (conflicts) {
                throw new PluginCatalogException(
                        "Plugin index output must not be inside or replace an input artifact");
            }
        }
    }

    private static Path canonicalTarget(Path output) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(absolute)) {
                throw new PluginCatalogException(
                        "Plugin index output must not be a symbolic link");
            }
            return absolute.toRealPath();
        }
        Path ancestor = absolute.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            return absolute;
        }
        return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
    }

    /**
     * Runs the build-time generator command.
     *
     * @param args {@code --output <index.json> [--strict-bundle-closures]
     *             [--bundle-closure <bundle-id> <artifact>]... -- [artifact ...]}
     * @throws Exception if arguments, inputs, metadata, or output are invalid
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !"--output".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Usage: PluginIndexGenerator --output <index.json> "
                            + "[--strict-bundle-closures] "
                            + "[--bundle-closure <bundle-id> <artifact>]... -- [artifact ...]");
        }
        Path output = Path.of(args[1]);
        boolean strictClosures = false;
        Map<String, List<Path>> closures = new LinkedHashMap<>();
        List<Path> artifacts = new ArrayList<>();
        int i = 2;
        while (i < args.length && !"--".equals(args[i])) {
            if ("--strict-bundle-closures".equals(args[i])) {
                strictClosures = true;
                i++;
            } else if ("--bundle-closure".equals(args[i])) {
                if (i + 2 >= args.length) {
                    throw new IllegalArgumentException(
                            "--bundle-closure requires a bundle id and artifact path");
                }
                strictClosures = true;
                closures.computeIfAbsent(args[i + 1], ignored -> new ArrayList<>())
                        .add(Path.of(args[i + 2]));
                i += 3;
            } else {
                break;
            }
        }
        if (i < args.length && "--".equals(args[i])) {
            i++;
        }
        for (; i < args.length; i++) {
            artifacts.add(Path.of(args[i]));
        }
        PluginIndexGenerator generator = new PluginIndexGenerator();
        if (strictClosures) {
            generator.writeArtifactClosures(artifacts, closures, output);
        } else {
            generator.write(artifacts, output);
        }
    }

    private static List<Path> canonicalArtifacts(Collection<Path> artifacts) throws IOException {
        List<Path> canonical = new ArrayList<>(artifacts.size());
        Set<Path> seen = new HashSet<>();
        for (Path artifact : artifacts) {
            Path requested = Objects.requireNonNull(
                    artifact, "artifacts must not contain null").toAbsolutePath().normalize();
            BasicFileAttributes attributes = Files.readAttributes(
                    requested, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new PluginCatalogException(
                        "Plugin artifact must not be a symbolic link: " + requested.getFileName());
            }
            Path value = requested.toRealPath();
            if (!seen.add(value)) {
                throw new PluginCatalogException("Plugin artifact path is repeated: " + value.getFileName());
            }
            canonical.add(value);
        }
        canonical.sort(Comparator.comparing(Path::toString));
        return List.copyOf(canonical);
    }

    private record ScanResult(PluginIndex index, Map<String, Path> bundleSources) {
    }
}
