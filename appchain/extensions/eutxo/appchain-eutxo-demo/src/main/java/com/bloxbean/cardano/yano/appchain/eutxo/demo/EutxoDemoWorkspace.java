package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verified demo workspace and its public/secret boundary. */
public final class EutxoDemoWorkspace {
    static final String MARKER = "YANO_EUTXO_DEMO_WORKSPACE_V1\n";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path root;
    private final EutxoDemoManifest manifest;

    private EutxoDemoWorkspace(Path root, EutxoDemoManifest manifest) {
        this.root = root;
        this.manifest = manifest;
    }

    public static EutxoDemoWorkspace create(
            EutxoDemoOptions options,
            EutxoDemoScenarioProvider provider) throws IOException {
        Path root = safeRoot(options.workspace());
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("demo workspace cannot be a symbolic link");
            }
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalStateException(
                            "demo workspace must not already contain files");
                }
            }
        } else {
            Files.createDirectories(root);
        }
        Files.writeString(root.resolve(".yano-eutxo-demo"), MARKER,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        for (String directory : List.of(
                "project", "secrets/members", "secrets/cardano", "secrets/l2",
                "runtime/journal", "runtime/validity", "runtime/locks",
                "artifacts/l1", "artifacts/l2", "artifacts/proofs",
                "artifacts/reports")) {
            Path created = root.resolve(directory);
            if (directory.startsWith("secrets/")) {
                EutxoDemoIdentityService.ownerDirectory(created);
            } else {
                Files.createDirectories(created);
            }
        }
        List<String> memberKeys = new EutxoDemoIdentityService()
                .generateMembers(root.resolve("secrets/members"), options.members());
        Map<String, String> secretReferences = new LinkedHashMap<>();
        for (int index = 0; index < options.members(); index++) {
            secretReferences.put("member" + index,
                    "secrets/members/node" + index + ".env");
        }
        String implementationVersion = EutxoDemoWorkspace.class.getPackage()
                .getImplementationVersion();
        EutxoDemoManifest manifest = new EutxoDemoManifest(
                1,
                implementationVersion == null ? "development" : implementationVersion,
                provider.id(),
                provider.version(),
                provider.getClass().getName(),
                provider.maturity(),
                "devnet",
                options.name(),
                options.chainId(),
                options.members(),
                options.httpPortBase(),
                options.serverPortBase(),
                memberKeys,
                Map.of("trustBoundary", provider.trustBoundary()),
                secretReferences,
                Instant.now().toString());
        writeManifest(root, manifest);
        Files.writeString(root.resolve(".gitignore"),
                "secrets/\nruntime/\nproject/data/\nproject/logs/\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new EutxoDemoWorkspace(root, manifest);
    }

    public static EutxoDemoWorkspace open(Path requested) throws IOException {
        Path root = safeRoot(requested);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("demo workspace does not exist");
        }
        Path marker = root.resolve(".yano-eutxo-demo");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !MARKER.equals(Files.readString(marker))) {
            throw new IllegalArgumentException(
                    "directory is not a verified Yano EUTxO demo workspace");
        }
        EutxoDemoManifest manifest =
                YAML.readValue(root.resolve("demo.yaml").toFile(), EutxoDemoManifest.class);
        if (manifest.schemaVersion() != 1 || !"devnet".equals(manifest.network())) {
            throw new IllegalStateException("unsupported EUTxO demo manifest");
        }
        return new EutxoDemoWorkspace(root, manifest);
    }

    public Path root() {
        return root;
    }

    public Path project() {
        return root.resolve("project");
    }

    public EutxoDemoManifest manifest() {
        return manifest;
    }

    public EutxoDemoJournal journal() {
        return new EutxoDemoJournal(root);
    }

    public void reset() throws IOException {
        rejectBroadDeletionTarget(root);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void writeManifest(Path root, EutxoDemoManifest manifest)
            throws IOException {
        Path target = root.resolve("demo.yaml");
        Path temporary = root.resolve("demo.yaml.tmp");
        YAML.writeValue(temporary.toFile(), manifest);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target);
        }
    }

    private static Path safeRoot(Path requested) {
        if (requested == null) {
            throw new IllegalArgumentException("demo workspace is required");
        }
        Path root = requested.toAbsolutePath().normalize();
        if (root.getParent() == null) {
            throw new IllegalArgumentException("demo workspace is too broad");
        }
        return root;
    }

    private static void rejectBroadDeletionTarget(Path root) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path current = Path.of("").toAbsolutePath().normalize();
        String configured = System.getenv("YANO_HOME");
        Path yanoHome = configured == null || configured.isBlank()
                ? null : Path.of(configured).toAbsolutePath().normalize();
        if (root.equals(home) || root.equals(current)
                || (yanoHome != null && root.equals(yanoHome))
                || root.getParent() == null) {
            throw new IllegalArgumentException("refusing to reset a broad directory");
        }
    }
}
