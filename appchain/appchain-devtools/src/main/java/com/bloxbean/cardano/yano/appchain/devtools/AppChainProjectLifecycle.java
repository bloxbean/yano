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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only project lifecycle checks shared by validate, doctor, diff, and migrate. */
final class AppChainProjectLifecycle {
    private static final int MAX_LOCK_BYTES = 4 * 1_048_576;
    private static final int MAX_ARCHIVE_ENTRIES = 50_000;
    private static final String RELEASE_INDEX_NAME =
            "appchain-release-capability-index.json";

    private final AppChainPropertyRegistry properties;
    private final AppChainProjectCatalog catalog;
    private final AppChainProjectRenderer renderer;
    private final ObjectMapper json;

    AppChainProjectLifecycle(AppChainPropertyRegistry properties) throws IOException {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.catalog = new AppChainProjectCatalog(properties);
        this.renderer = new AppChainProjectRenderer(catalog,
                new AppChainProjectResolver(properties, catalog));
        this.json = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
                        + catalog.releaseIndex().artifacts().size() + " artifacts"));

        AppChainProjectModel.Lock projectLock = null;
        if (project != null) {
            try {
                AppChainProjectModel.ProjectValidation validation = validate(project);
                projectLock = validation.lock();
                checks.add(check("project", "PASS", validation.generatedFileCount()
                        + " generated files verified"));
                String toolVersion = AppChainProjectLifecycle.class.getPackage()
                        .getImplementationVersion();
                if (toolVersion != null && !toolVersion.isBlank()) {
                    checks.add(check("tool-version",
                            toolVersion.equals(projectLock.yanoVersion()) ? "PASS" : "FAIL",
                            "project=" + projectLock.yanoVersion() + ", tooling=" + toolVersion));
                }
                for (String acknowledgement : safeList(projectLock.acknowledgements())) {
                    String status = acknowledgement.startsWith("PUBLIC_MEMBER_") ? "FAIL" : "WARN";
                    checks.add(check("acknowledgement", status, acknowledgement));
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                checks.add(check("project", "FAIL", safeMessage(failure)));
            }
        }

        if (distribution != null) {
            try {
                DistributionInspection inspected = inspectDistribution(distribution);
                String expectedDigest = catalog.digests().get("releaseIndex");
                checks.add(check("distribution-index",
                        expectedDigest.equals(inspected.releaseIndexDigest()) ? "PASS" : "FAIL",
                        inspected.runtime() + " release capability index"));
                if (projectLock != null) {
                    checks.add(check("distribution-runtime",
                            projectLock.runtime().equals(inspected.runtime()) ? "PASS" : "FAIL",
                            "project=" + projectLock.runtime() + ", distribution="
                                    + inspected.runtime()));
                    for (String artifact : safeList(projectLock.artifacts())) {
                        checks.add(check("artifact:" + artifact,
                                catalog.releaseIndex().artifacts().contains(artifact)
                                        ? "PASS" : "FAIL",
                                "declared by release capability index"));
                    }
                }
            } catch (IOException | IllegalArgumentException failure) {
                checks.add(check("distribution", "FAIL", safeMessage(failure)));
            }
        }

        String status = checks.stream().anyMatch(item -> "FAIL".equals(item.status()))
                ? "DOCTOR_FAILED"
                : checks.stream().anyMatch(item -> "WARN".equals(item.status()))
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

    private DistributionInspection inspectDistribution(Path distribution) throws IOException {
        if (Files.isDirectory(distribution, LinkOption.NOFOLLOW_LINKS)) {
            Path index = findReleaseIndex(distribution);
            String runtime = Files.isRegularFile(distribution.resolve("yano.jar")) ? "jvm"
                    : Files.isRegularFile(distribution.resolve("yano"))
                    || Files.isRegularFile(distribution.resolve("yano.exe")) ? "native" : null;
            if (runtime == null) throw new IOException("distribution runtime executable is missing");
            return new DistributionInspection(runtime,
                    AppChainProjectCatalog.sha256(Files.readAllBytes(index)));
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
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith("/yano.jar")) jvm = true;
                if (!entry.isDirectory() && (name.endsWith("/yano")
                        || name.endsWith("/yano.exe"))) nativeRuntime = true;
                if (!entry.isDirectory() && name.endsWith("/" + RELEASE_INDEX_NAME)) {
                    if (indexEntry != null) {
                        throw new IOException("distribution contains multiple release indexes");
                    }
                    indexEntry = entry;
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
                json.readValue(bytes, AppChainProjectModel.ReleaseIndex.class);
                return new DistributionInspection(jvm ? "jvm" : "native",
                        AppChainProjectCatalog.sha256(bytes));
            }
        }
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

    private record DistributionInspection(String runtime, String releaseIndexDigest) {
    }
}
