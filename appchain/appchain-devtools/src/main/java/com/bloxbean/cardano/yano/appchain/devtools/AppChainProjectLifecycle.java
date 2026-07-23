package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.bloxbean.cardano.yano.appchain.config.ChangePolicy;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only project lifecycle checks shared by validate, doctor, diff, and migrate. */
final class AppChainProjectLifecycle {
    private static final int MAX_LOCK_BYTES = 4 * 1_048_576;
    private static final int MAX_ARCHIVE_ENTRIES = 50_000;
    private static final int MAX_PLUGIN_ARTIFACTS = 128;
    private static final long MAX_PLUGIN_ARTIFACT_BYTES = 128L * 1_048_576;
    private static final String RELEASE_INDEX_NAME =
            "appchain-release-capability-index.json";

    private final AppChainPropertyRegistry properties;
    private final AppChainProjectCatalog catalog;
    private final AppChainProjectRenderer renderer;
    private final AppChainProjectResolver projectResolver;
    private final ObjectMapper json;

    AppChainProjectLifecycle(AppChainPropertyRegistry properties) throws IOException {
        this(properties, new AppChainProjectCatalog(properties));
    }

    AppChainProjectLifecycle(
            AppChainPropertyRegistry properties,
            AppChainProjectCatalog catalog) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.projectResolver = new AppChainProjectResolver(properties, catalog);
        this.renderer = new AppChainProjectRenderer(catalog, projectResolver);
        this.json = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static AppChainProjectLifecycle forProject(
            AppChainPropertyRegistry properties, Path project) throws IOException {
        List<AppChainComponentCatalogLoader.Loaded> external =
                new AppChainComponentCatalogLoader().loadProject(projectRoot(project));
        AppChainPropertyRegistry extended = new AppChainComponentCatalogLoader()
                .extendRegistry(properties, external);
        return new AppChainProjectLifecycle(extended,
                new AppChainProjectCatalog(extended, external));
    }

    AppChainProjectModel.Lock render(Path project) throws IOException {
        return renderer.render(projectRoot(project));
    }

    AppChainProjectModel.ProjectValidation validate(Path project) throws IOException {
        return renderer.validate(projectRoot(project));
    }

    AppChainProjectModel.LockDiff diff(Path beforePath, Path afterPath) throws IOException {
        AppChainProjectModel.Lock before = readLockFile(beforePath);
        AppChainProjectModel.Lock after = readLockFile(afterPath);
        List<AppChainProjectModel.LockChange> changes = new ArrayList<>();
        compare(changes, "project.yano-version", before.yanoVersion(), after.yanoVersion(),
                ChangePolicy.UNSUPPORTED);
        compare(changes, "project.runtime", before.runtime(), after.runtime(),
                ChangePolicy.UNSUPPORTED);
        compare(changes, "project.deployment", before.deployment(), after.deployment(),
                ChangePolicy.RESTART_REQUIRED);
        compare(changes, "project.network", before.network(), after.network(),
                ChangePolicy.NEW_CHAIN_REQUIRED);
        compare(changes, "project.recipe", before.recipe(), after.recipe(),
                ChangePolicy.NEW_CHAIN_REQUIRED);
        compare(changes, "project.capabilities", before.selectedCapabilities(),
                after.selectedCapabilities(), ChangePolicy.UNSUPPORTED);
        compare(changes, "project.artifacts", before.artifacts(), after.artifacts(),
                ChangePolicy.UNSUPPORTED);

        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(safeMap(before.consensusValues()).keySet());
        keys.addAll(safeMap(after.consensusValues()).keySet());
        for (String key : keys) {
            String oldValue = safeMap(before.consensusValues()).get(key);
            String newValue = safeMap(after.consensusValues()).get(key);
            if (!Objects.equals(oldValue, newValue)) {
                ChangePolicy policy = properties.find(key)
                        .map(match -> match.definition().changePolicy())
                        .orElse(ChangePolicy.UNSUPPORTED);
                changes.add(new AppChainProjectModel.LockChange(key, policy.name()));
            }
        }
        changes.sort(java.util.Comparator.comparing(AppChainProjectModel.LockChange::key));
        Map<String, Integer> categories = new TreeMap<>();
        for (AppChainProjectModel.LockChange change : changes) {
            categories.merge(change.policy(), 1, Integer::sum);
        }
        return new AppChainProjectModel.LockDiff(
                changes.isEmpty() ? "NO_CHANGE" : "CHANGESET",
                List.copyOf(changes), Map.copyOf(categories));
    }

    AppChainProjectModel.DoctorReport doctor(Path project, Path distribution) {
        List<AppChainProjectModel.DoctorCheck> checks = new ArrayList<>();
        checks.add(check("java", Runtime.version().feature() >= 25 ? "PASS" : "FAIL",
                "Java " + Runtime.version().feature() + " (25 or newer required)"));
        checks.add(check("release-index", "PASS",
                catalog.releaseIndex().recipes().size() + " recipes, "
                        + catalog.releaseIndex().artifacts().size() + " bundled artifacts, "
                        + catalog.capabilities().size() + " capabilities"));

        AppChainProjectModel.Lock projectLock = null;
        String configStage = project == null ? "NOT_REQUIRED" : "FAIL";
        String artifactStage = distribution == null ? "PENDING" : "FAIL";
        String identityStage = "NOT_REQUIRED";
        if (project != null) {
            try {
                AppChainProjectModel.ProjectValidation validation = validate(project);
                projectLock = validation.lock();
                configStage = "PASS";
                checks.add(check("project", "PASS", validation.generatedFileCount()
                        + " generated files verified"));
                String toolVersion = AppChainProjectLifecycle.class.getPackage()
                        .getImplementationVersion();
                if (toolVersion != null && !toolVersion.isBlank()) {
                    boolean versionMatch = toolVersion.equals(projectLock.yanoVersion());
                    checks.add(check("tool-version", versionMatch ? "PASS" : "FAIL",
                            "project=" + projectLock.yanoVersion() + ", tooling=" + toolVersion));
                    if (!versionMatch) configStage = "FAIL";
                }
                for (String acknowledgement : safeList(projectLock.acknowledgements())) {
                    checks.add(check("acknowledgement", "WARN", acknowledgement));
                }
                identityStage = safeList(projectLock.acknowledgements()).stream()
                        .anyMatch(value -> value.startsWith("PUBLIC_MEMBER_"))
                        ? "PENDING" : "PASS";
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                checks.add(check("project", "FAIL", safeMessage(failure)));
            }
        }

        if (distribution != null) {
            try {
                DistributionInspection inspected = inspectDistribution(distribution);
                String expectedDigest = catalog.digests().get("releaseIndex");
                boolean indexMatch = expectedDigest.equals(inspected.releaseIndexDigest());
                checks.add(check("distribution-index", indexMatch ? "PASS" : "FAIL",
                        inspected.runtime() + " release capability index"));
                if (projectLock != null) {
                    boolean runtimeMatch = projectLock.runtime().equals(inspected.runtime());
                    checks.add(check("distribution-runtime", runtimeMatch ? "PASS" : "FAIL",
                            "project=" + projectLock.runtime() + ", distribution="
                                    + inspected.runtime()));
                    boolean artifactFailure = false;
                    boolean artifactPending = false;
                    for (String artifact : safeList(projectLock.artifacts())) {
                        String availability = catalog.artifact(artifact).availability();
                        boolean external = catalog.isExternalArtifact(artifact);
                        boolean present = external
                                ? inspected.pluginArtifactDigests().contains(
                                catalog.externalArtifactDigest(artifact))
                                : inspected.releaseIndex().artifacts().contains(artifact);
                        String artifactStatus;
                        String detail;
                        if (external && "native".equals(inspected.runtime())) {
                            artifactStatus = "FAIL";
                            detail = "custom JVM plugin cannot be loaded by a native executable; "
                                    + "publish a build-time native flavor and release index";
                        } else if (present) {
                            artifactStatus = "PASS";
                            detail = external ? "pinned plugin digest found in distribution plugins/"
                                    : "bundled by inspected release";
                        } else if (external || "BUNDLED".equals(availability)) {
                            artifactStatus = "FAIL";
                            detail = external ? "pinned plugin digest is absent from distribution plugins/"
                                    : "BUNDLED artifact is not present in inspected release";
                        } else {
                            artifactStatus = "PENDING";
                            detail = availability + " artifact is not bundled by inspected release";
                        }
                        artifactFailure |= "FAIL".equals(artifactStatus);
                        artifactPending |= "PENDING".equals(artifactStatus);
                        checks.add(check("artifact:" + artifact, artifactStatus, detail));
                    }
                    artifactStage = !runtimeMatch || !indexMatch || artifactFailure
                            ? "FAIL" : artifactPending ? "PENDING" : "PASS";
                }
            } catch (IOException | IllegalArgumentException failure) {
                checks.add(check("distribution", "FAIL", safeMessage(failure)));
            }
        }

        if (project != null) {
            checks.add(check("CONFIG_VALID", configStage,
                    "blueprint, lock, catalogs, configuration, and generated-file digests"));
            checks.add(check("ARTIFACTS_READY", artifactStage,
                    distribution == null
                            ? "provide --distribution to verify required artifacts"
                            : "required artifacts checked against the inspected distribution"));
            checks.add(check("IDENTITIES_READY", identityStage,
                    "public member identities must be pinned before runtime start"));

            List<AppChainProjectModel.Capability> selected = projectLock == null ? List.of()
                    : safeList(projectLock.selectedCapabilities()).stream()
                    .map(catalog::capability).toList();
            boolean hardFailure = "FAIL".equals(configStage) || "FAIL".equals(artifactStage);
            boolean runtimeReady = "PASS".equals(configStage)
                    && "PASS".equals(artifactStage) && "PASS".equals(identityStage);
            checks.add(check("RUNTIME_STARTABLE", hardFailure ? "FAIL"
                            : runtimeReady ? "PASS" : "PENDING",
                    runtimeReady ? "configuration, artifacts, and identities are ready"
                            : "resolve earlier readiness stages before starting nodes"));

            List<String> bootstrap = selected.stream()
                    .flatMap(capability -> safeList(capability.bootstrapRequirements()).stream())
                    .distinct().sorted().toList();
            String applicationStage = bootstrap.isEmpty() ? "NOT_REQUIRED" : "PENDING";
            checks.add(check("APPLICATION_BOOTSTRAPPED", applicationStage,
                    bootstrap.isEmpty() ? "selected recipe requires no application bootstrap"
                            : "verify generated bootstrap plan: " + String.join(", ", bootstrap)));

            List<String> executors = selected.stream()
                    .filter(capability -> "effect-executor".equals(capability.category()))
                    .map(AppChainProjectModel.Capability::id).sorted().toList();
            String executorStage = executors.isEmpty() ? "NOT_REQUIRED" : "PENDING";
            checks.add(check("EXECUTORS_READY", executorStage,
                    executors.isEmpty() ? "selected outcome requires no effect executor"
                            : "verify executor health after startup: " + String.join(", ", executors)));

            List<String> external = selected.stream()
                    .flatMap(capability -> safeList(capability.externalPrerequisites()).stream())
                    .distinct().sorted().toList();
            String targetStage = external.isEmpty() ? "NOT_REQUIRED" : "PENDING";
            checks.add(check("EXTERNAL_TARGETS_READY", targetStage,
                    external.isEmpty() ? "selected outcome requires no external target"
                            : "verify external prerequisites: " + String.join(", ", external)));

            boolean outcomeReady = runtimeReady
                    && "NOT_REQUIRED".equals(applicationStage)
                    && "NOT_REQUIRED".equals(executorStage)
                    && "NOT_REQUIRED".equals(targetStage);
            checks.add(check("OUTCOME_READY", hardFailure ? "FAIL"
                            : outcomeReady ? "PASS" : "PENDING",
                    outcomeReady ? "selected recipe can perform its first useful outcome"
                            : "one or more runtime, bootstrap, executor, or target stages remain"));
        }

        String status = checks.stream().anyMatch(item -> "FAIL".equals(item.status()))
                ? "DOCTOR_FAILED"
                : checks.stream().anyMatch(item -> "WARN".equals(item.status())
                        || "PENDING".equals(item.status()))
                ? "DOCTOR_WARNINGS" : "DOCTOR_OK";
        return new AppChainProjectModel.DoctorReport(status, List.copyOf(checks));
    }

    String migrate(Path project, boolean dryRun) throws IOException {
        AppChainProjectModel.ProjectValidation validation = validate(project);
        if (!AppChainProjectModel.API_VERSION.equals(validation.lock().apiVersion())) {
            throw new IOException("No automatic migration adapter for "
                    + validation.lock().apiVersion());
        }
        return dryRun ? "NO_MIGRATION_REQUIRED_DRY_RUN" : "NO_MIGRATION_REQUIRED";
    }

    AppChainProjectModel.DriftReport drift(
            Path project,
            List<URI> peers,
            String apiKey) throws IOException {
        Path root = projectRoot(project);
        AppChainProjectModel.ProjectValidation validation = renderer.validate(root);
        AppChainProjectModel.Blueprint blueprint = renderer.readBlueprint(root);
        if (blueprint.spec() == null || blueprint.spec().chains() == null
                || blueprint.spec().chains().size() != 1) {
            throw new IllegalArgumentException(
                    "drift currently requires exactly one chain in the project blueprint");
        }
        String chainId = blueprint.spec().chains().getFirst().chainId();
        return new AppChainDriftClient().compare(
                validation.lock(), chainId, peers, apiKey);
    }

    AppChainProjectModel.GitOpsResult gitOps(
            Path project,
            AppChainGitOpsExporter.Target target,
            Path output) throws IOException {
        return new AppChainGitOpsExporter(renderer, projectResolver)
                .export(projectRoot(project), target, output);
    }

    private DistributionInspection inspectDistribution(Path distribution) throws IOException {
        if (Files.isDirectory(distribution, LinkOption.NOFOLLOW_LINKS)) {
            Path index = findReleaseIndex(distribution);
            String runtime = Files.isRegularFile(distribution.resolve("yano.jar")) ? "jvm"
                    : Files.isRegularFile(distribution.resolve("yano"))
                    || Files.isRegularFile(distribution.resolve("yano.exe")) ? "native" : null;
            if (runtime == null) throw new IOException("distribution runtime executable is missing");
            byte[] bytes = Files.readAllBytes(index);
            return new DistributionInspection(runtime,
                    AppChainProjectCatalog.sha256(bytes),
                    json.readValue(bytes, AppChainProjectModel.ReleaseIndex.class),
                    inspectPluginDirectory(distribution.resolve("plugins")));
        }
        if (!Files.isRegularFile(distribution, LinkOption.NOFOLLOW_LINKS)
                || !distribution.getFileName().toString().endsWith(".zip")) {
            throw new IOException("distribution must be an extracted directory or zip archive");
        }
        try (ZipFile archive = new ZipFile(distribution.toFile())) {
            if (archive.size() > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("distribution archive contains too many entries");
            }
            boolean jvm = false;
            boolean nativeRuntime = false;
            ZipEntry indexEntry = null;
            List<ZipEntry> pluginEntries = new ArrayList<>();
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && archivePathEndsWith(name, "yano.jar")) jvm = true;
                if (!entry.isDirectory() && (archivePathEndsWith(name, "yano")
                        || archivePathEndsWith(name, "yano.exe"))) nativeRuntime = true;
                if (!entry.isDirectory() && archivePathEndsWith(name, RELEASE_INDEX_NAME)) {
                    if (indexEntry != null) {
                        throw new IOException("distribution contains multiple release indexes");
                    }
                    indexEntry = entry;
                }
                if (!entry.isDirectory() && isPluginArchiveEntry(name)) {
                    pluginEntries.add(entry);
                    if (pluginEntries.size() > MAX_PLUGIN_ARTIFACTS) {
                        throw new IOException("distribution contains too many plugin artifacts");
                    }
                }
            }
            if (jvm == nativeRuntime) {
                throw new IOException("distribution runtime type is ambiguous or missing");
            }
            if (indexEntry == null || indexEntry.getSize() > AppChainProjectCatalog.MAX_RESOURCE_BYTES) {
                throw new IOException("distribution release capability index is missing or oversized");
            }
            try (InputStream input = archive.getInputStream(indexEntry)) {
                byte[] bytes = input.readNBytes(AppChainProjectCatalog.MAX_RESOURCE_BYTES + 1);
                if (bytes.length > AppChainProjectCatalog.MAX_RESOURCE_BYTES) {
                    throw new IOException("distribution release capability index is oversized");
                }
                AppChainProjectModel.ReleaseIndex releaseIndex = json.readValue(
                        bytes, AppChainProjectModel.ReleaseIndex.class);
                return new DistributionInspection(jvm ? "jvm" : "native",
                        AppChainProjectCatalog.sha256(bytes), releaseIndex,
                        inspectPluginEntries(archive, pluginEntries));
            }
        }
    }

    private static Set<String> inspectPluginDirectory(Path plugins) throws IOException {
        if (!Files.exists(plugins, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        if (Files.isSymbolicLink(plugins)
                || !Files.isDirectory(plugins, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("distribution plugins path must be a non-symlink directory");
        }
        Set<String> digests = new LinkedHashSet<>();
        try (var contents = Files.list(plugins)) {
            List<Path> files = contents.sorted().toList();
            if (files.size() > MAX_PLUGIN_ARTIFACTS) {
                throw new IOException("distribution contains too many plugin artifacts");
            }
            for (Path file : files) {
                String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".jar") && !name.endsWith(".zip")) continue;
                if (Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) > MAX_PLUGIN_ARTIFACT_BYTES) {
                    throw new IOException("distribution plugin artifact is unsafe or oversized");
                }
                try (InputStream input = Files.newInputStream(file)) {
                    digests.add(sha256(input, MAX_PLUGIN_ARTIFACT_BYTES));
                }
            }
        }
        return Set.copyOf(digests);
    }

    private static Set<String> inspectPluginEntries(ZipFile archive, List<ZipEntry> entries)
            throws IOException {
        Set<String> digests = new LinkedHashSet<>();
        for (ZipEntry entry : entries) {
            if (entry.getSize() < 0 || entry.getSize() > MAX_PLUGIN_ARTIFACT_BYTES) {
                throw new IOException("distribution plugin artifact is oversized");
            }
            try (InputStream input = archive.getInputStream(entry)) {
                digests.add(sha256(input, MAX_PLUGIN_ARTIFACT_BYTES));
            }
        }
        return Set.copyOf(digests);
    }

    private static String sha256(InputStream input, long limit) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > limit) throw new IOException("plugin artifact exceeds size limit");
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean isPluginArchiveEntry(String path) {
        String normalized = path.replace('\\', '/');
        int marker = normalized.lastIndexOf("/plugins/");
        if (marker < 0) return normalized.startsWith("plugins/")
                && normalized.substring("plugins/".length()).indexOf('/') < 0
                && isJarOrZip(normalized);
        String leaf = normalized.substring(marker + "/plugins/".length());
        return !leaf.isEmpty() && leaf.indexOf('/') < 0 && isJarOrZip(leaf);
    }

    private static boolean isJarOrZip(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    private static boolean archivePathEndsWith(String path, String fileName) {
        return path.equals(fileName) || path.endsWith("/" + fileName);
    }

    private static Path findReleaseIndex(Path root) throws IOException {
        List<Path> candidates = List.of(
                root.resolve("tools/yano-appchain/metadata/appchain-dx/v1alpha1")
                        .resolve(RELEASE_INDEX_NAME),
                root.resolve("config/schema").resolve(RELEASE_INDEX_NAME));
        List<Path> existing = candidates.stream().filter(Files::isRegularFile).toList();
        if (existing.size() != 1) {
            throw new IOException("distribution must contain exactly one release capability index");
        }
        return existing.getFirst();
    }

    private AppChainProjectModel.Lock readLockFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_LOCK_BYTES) {
            throw new IOException("lock is missing or exceeds the safety limit");
        }
        AppChainProjectModel.Lock lock = json.readValue(Files.readAllBytes(path),
                AppChainProjectModel.Lock.class);
        if (!AppChainProjectModel.API_VERSION.equals(lock.apiVersion())
                || !AppChainProjectModel.LOCK_KIND.equals(lock.kind())) {
            throw new IOException("lock uses an unsupported schema");
        }
        return lock;
    }

    private static Path projectRoot(Path project) {
        Path absolute = project.toAbsolutePath().normalize();
        return absolute.getFileName() != null
                && AppChainProjectRenderer.BLUEPRINT_FILE.equals(absolute.getFileName().toString())
                ? absolute.getParent() : absolute;
    }

    private static void compare(
            List<AppChainProjectModel.LockChange> changes,
            String key,
            Object before,
            Object after,
            ChangePolicy policy) {
        if (!Objects.equals(before, after)) {
            changes.add(new AppChainProjectModel.LockChange(key, policy.name()));
        }
    }

    private static AppChainProjectModel.DoctorCheck check(
            String id, String status, String detail) {
        return new AppChainProjectModel.DoctorCheck(id, status, detail);
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        String first = newline < 0 ? message : message.substring(0, newline);
        return first.codePoints().limit(256)
                .collect(StringBuilder::new,
                        (builder, codePoint) -> builder.appendCodePoint(
                                Character.isISOControl(codePoint) ? '?' : codePoint),
                        StringBuilder::append).toString();
    }

    private static <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record DistributionInspection(
            String runtime,
            String releaseIndexDigest,
            AppChainProjectModel.ReleaseIndex releaseIndex,
            Set<String> pluginArtifactDigests) {
    }
}
