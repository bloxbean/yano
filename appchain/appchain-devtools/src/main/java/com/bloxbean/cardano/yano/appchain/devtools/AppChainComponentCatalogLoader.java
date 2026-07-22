package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.roles.contracts.internal.RoleWorkflowEd25519;
import com.bloxbean.cardano.yano.appchain.config.AppChainMetadataSource;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict resource-only loader for signed custom component product catalogs. */
final class AppChainComponentCatalogLoader {
    static final String CATALOG_PATH = "META-INF/yano/appchain-component-catalog-v1.json";
    static final String SIGNATURE_PATH =
            "META-INF/yano/appchain-component-catalog-v1.sig.json";
    static final String SNAPSHOT_KIND = "AppChainComponentCatalogSnapshot";
    static final int MAX_CATALOGS = 16;

    private static final String MANIFEST_PREFIX = "META-INF/yano/plugins/";
    private static final int MAX_ENTRY_BYTES = 1_048_576;
    private static final int MAX_SNAPSHOT_BYTES = 4 * 1_048_576;
    private static final int MAX_ARTIFACT_BYTES = 128 * 1_048_576;
    private static final int MAX_ARCHIVE_ENTRIES = 50_000;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9.-]{0,127}");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    Loaded loadJar(Path artifact, Map<String, String> trustKeys) throws IOException {
        requireArtifact(artifact);
        validateTrustKeys(trustKeys);
        String artifactDigest = sha256File(artifact, MAX_ARTIFACT_BYTES, "plugin artifact");
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            if (archive.size() > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("plugin archive contains too many entries");
            }
            byte[] catalogBytes = readBounded(archive, unique(archive, CATALOG_PATH),
                    "component catalog");
            byte[] signatureBytes = readBounded(archive, unique(archive, SIGNATURE_PATH),
                    "component catalog signature");
            Catalog catalog = parseCatalog(catalogBytes);
            TrustEnvelope envelope = json.readValue(signatureBytes, TrustEnvelope.class);
            validateEnvelope(envelope, catalog);
            String manifestPath = MANIFEST_PREFIX + catalog.bundleId() + ".json";
            byte[] manifestBytes = readBounded(archive,
                    uniqueRuntimeManifest(archive, manifestPath), "runtime manifest");
            validateRuntimeManifest(manifestBytes, catalog.bundleId(), catalog.bundleVersion());
            ZipEntry metadataEntry = uniqueOptional(
                    archive, com.bloxbean.cardano.yano.appchain.config
                            .AppChainMetadataDescriptor.RESOURCE_PATH);
            byte[] metadataBytes = metadataEntry == null ? new byte[0]
                    : readBounded(archive, metadataEntry, "configuration metadata");
            verifyBinding(catalogBytes, manifestBytes, metadataBytes,
                    envelope, catalog, trustKeys);

            Snapshot snapshot = new Snapshot(1, SNAPSHOT_KIND,
                    artifact.getFileName().toString(), artifactDigest,
                    Base64.getEncoder().encodeToString(catalogBytes),
                    Base64.getEncoder().encodeToString(manifestBytes),
                    Base64.getEncoder().encodeToString(metadataBytes), envelope);
            byte[] snapshotBytes = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(snapshot);
            return loaded(catalog, envelope, snapshot, snapshotBytes,
                    catalogBytes, manifestBytes, metadataBytes);
        }
    }

    Loaded loadSnapshot(Path snapshotFile, Map<String, String> trustKeys) throws IOException {
        validateTrustKeys(trustKeys);
        byte[] snapshotBytes = boundedFile(snapshotFile, MAX_SNAPSHOT_BYTES,
                "component catalog snapshot");
        Snapshot snapshot = json.readValue(snapshotBytes, Snapshot.class);
        if (snapshot.schemaVersion() != 1 || !SNAPSHOT_KIND.equals(snapshot.kind())
                || snapshot.artifactFileName() == null
                || !snapshot.artifactFileName().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")
                || !isSha256(snapshot.artifactSha256()) || snapshot.trust() == null) {
            throw new IllegalArgumentException("component catalog snapshot is invalid");
        }
        byte[] catalogBytes = decode(snapshot.catalogBase64(), "component catalog");
        byte[] manifestBytes = decode(snapshot.runtimeManifestBase64(), "runtime manifest");
        byte[] metadataBytes = decode(snapshot.configurationMetadataBase64(),
                "configuration metadata");
        Catalog catalog = parseCatalog(catalogBytes);
        validateEnvelope(snapshot.trust(), catalog);
        validateRuntimeManifest(manifestBytes, catalog.bundleId(), catalog.bundleVersion());
        verifyBinding(catalogBytes, manifestBytes, metadataBytes,
                snapshot.trust(), catalog, trustKeys);
        return loaded(catalog, snapshot.trust(), snapshot, snapshotBytes,
                catalogBytes, manifestBytes, metadataBytes);
    }

    List<Loaded> loadProject(Path project) throws IOException {
        Path root = projectRoot(project);
        Path blueprintFile = root.resolve(AppChainProjectRenderer.BLUEPRINT_FILE);
        byte[] blueprintBytes = boundedFile(blueprintFile,
                AppChainProjectRenderer.MAX_BLUEPRINT_BYTES, "blueprint");
        AppChainProjectModel.Blueprint blueprint = yaml.readValue(
                blueprintBytes, AppChainProjectModel.Blueprint.class);
        List<AppChainProjectModel.ComponentCatalogRef> references = blueprint.spec() == null
                ? List.of() : safeList(blueprint.spec().componentCatalogs());
        if (references.size() > MAX_CATALOGS) {
            throw new IllegalArgumentException("at most 16 component catalogs are supported");
        }
        List<Loaded> result = new ArrayList<>();
        Set<String> paths = new java.util.LinkedHashSet<>();
        for (AppChainProjectModel.ComponentCatalogRef reference : references) {
            if (reference == null || !KEY_ID.matcher(value(reference.trustedKeyId())).matches()
                    || !value(reference.trustedPublicKey()).matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("component catalog trust reference is invalid");
            }
            Path relative = safeRelative(reference.path());
            if (!paths.add(relative.toString())) {
                throw new IllegalArgumentException("duplicate component catalog path");
            }
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) {
                throw new IllegalArgumentException("component catalog path escapes project");
            }
            result.add(loadSnapshot(candidate,
                    Map.of(reference.trustedKeyId(), reference.trustedPublicKey())));
        }
        requireUnique(result);
        return List.copyOf(result);
    }

    AppChainPropertyRegistry extendRegistry(
            AppChainPropertyRegistry base,
            List<Loaded> catalogs) throws IOException {
        List<AppChainMetadataSource> sources = new ArrayList<>();
        for (AppChainMetadataSource source : base.sources()) {
            if (!AppChainPropertyRegistry.OWNER_CORE.equals(source.id())) sources.add(source);
        }
        AppChainDescriptorLoader descriptors = new AppChainDescriptorLoader();
        for (Loaded loaded : catalogs == null ? List.<Loaded>of() : catalogs) {
            if (loaded.configurationMetadataBytes().length == 0) continue;
            var descriptor = descriptors.loadMetadata(loaded.configurationMetadataBytes());
            if (!loaded.catalog().bundleId().equals(descriptor.id())) {
                throw new IllegalArgumentException(
                        "configuration metadata id must match the component bundle id");
            }
            sources.add(descriptor.toSource());
        }
        return AppChainPropertyRegistry.withSources(sources);
    }

    SignedCatalog sign(Path catalogFile, Path manifestFile, Path metadataFile,
                       String keyId, Path seedFile) throws IOException {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("publisher key id is invalid");
        }
        byte[] catalogBytes = boundedFile(catalogFile, MAX_ENTRY_BYTES, "component catalog");
        byte[] manifestBytes = boundedFile(manifestFile, MAX_ENTRY_BYTES, "runtime manifest");
        byte[] metadataBytes = metadataFile == null ? new byte[0]
                : boundedFile(metadataFile, MAX_ENTRY_BYTES, "configuration metadata");
        Catalog catalog = parseCatalog(catalogBytes);
        validateRuntimeManifest(manifestBytes, catalog.bundleId(), catalog.bundleVersion());
        byte[] seed = readSeed(seedFile);
        TrustEnvelope unsigned = new TrustEnvelope(1, "Ed25519", keyId,
                catalog.bundleId(), catalog.bundleVersion(),
                AppChainProjectCatalog.sha256(catalogBytes),
                AppChainProjectCatalog.sha256(manifestBytes),
                AppChainProjectCatalog.sha256(metadataBytes), "");
        byte[] signature = RoleWorkflowEd25519.sign(canonicalPayload(unsigned, catalog.catalogId()),
                seed);
        TrustEnvelope envelope = new TrustEnvelope(unsigned.schemaVersion(), unsigned.algorithm(),
                unsigned.keyId(), unsigned.bundleId(), unsigned.bundleVersion(),
                unsigned.catalogSha256(), unsigned.runtimeManifestSha256(),
                unsigned.configurationMetadataSha256(),
                Base64.getEncoder().encodeToString(signature));
        return new SignedCatalog(envelope,
                HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed)),
                json.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
    }

    void writeSignature(Path output, SignedCatalog signed) throws IOException {
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("signature output already exists");
        }
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, signed.envelopeBytes(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private Loaded loaded(Catalog catalog, TrustEnvelope envelope, Snapshot snapshot,
                          byte[] snapshotBytes, byte[] catalogBytes,
                          byte[] manifestBytes, byte[] metadataBytes) {
        return new Loaded(catalog, envelope, snapshot, snapshotBytes.clone(),
                AppChainProjectCatalog.sha256(snapshotBytes),
                AppChainProjectCatalog.sha256(catalogBytes),
                AppChainProjectCatalog.sha256(manifestBytes),
                AppChainProjectCatalog.sha256(metadataBytes), metadataBytes.clone());
    }

    private Catalog parseCatalog(byte[] bytes) throws IOException {
        Catalog catalog = json.readValue(bytes, Catalog.class);
        if (catalog == null || !"v1alpha1".equals(catalog.schemaVersion())
                || !ID.matcher(value(catalog.catalogId())).matches()
                || !ID.matcher(value(catalog.bundleId())).matches()
                || catalog.bundleVersion() == null || catalog.bundleVersion().isBlank()
                || catalog.bundleVersion().length() > 128 || catalog.artifact() == null
                || catalog.capabilities() == null || catalog.capabilities().isEmpty()
                || catalog.capabilities().size() > 64
                || !catalog.bundleId().equals(catalog.artifact().bundleId())) {
            throw new IllegalArgumentException("component catalog is invalid");
        }
        return catalog;
    }

    private void verifyBinding(byte[] catalogBytes, byte[] manifestBytes, byte[] metadataBytes,
                               TrustEnvelope envelope, Catalog catalog,
                               Map<String, String> trustKeys) {
        requireDigest("component catalog", envelope.catalogSha256(),
                AppChainProjectCatalog.sha256(catalogBytes));
        requireDigest("runtime manifest", envelope.runtimeManifestSha256(),
                AppChainProjectCatalog.sha256(manifestBytes));
        requireDigest("configuration metadata", envelope.configurationMetadataSha256(),
                AppChainProjectCatalog.sha256(metadataBytes));
        String publicKey = trustKeys.get(envelope.keyId());
        if (publicKey == null) {
            throw new IllegalArgumentException(
                    "component catalog signature key is not trusted: " + envelope.keyId());
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(envelope.signature());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("component catalog signature is not valid base64");
        }
        if (!RoleWorkflowEd25519.verify(signature,
                canonicalPayload(envelope, catalog.catalogId()),
                HexFormat.of().parseHex(publicKey))) {
            throw new IllegalArgumentException("component catalog signature verification failed");
        }
    }

    private static byte[] canonicalPayload(TrustEnvelope envelope, String catalogId) {
        return ("yano-appchain-component-catalog-trust-v1\n"
                + catalogId + "\n" + envelope.bundleId() + "\n"
                + envelope.bundleVersion() + "\n" + envelope.keyId() + "\n"
                + envelope.catalogSha256() + "\n" + envelope.runtimeManifestSha256() + "\n"
                + envelope.configurationMetadataSha256() + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void validateEnvelope(TrustEnvelope envelope, Catalog catalog) {
        if (envelope == null || envelope.schemaVersion() != 1
                || !"Ed25519".equals(envelope.algorithm())
                || !KEY_ID.matcher(value(envelope.keyId())).matches()
                || !catalog.bundleId().equals(envelope.bundleId())
                || !catalog.bundleVersion().equals(envelope.bundleVersion())
                || !isSha256(envelope.catalogSha256())
                || !isSha256(envelope.runtimeManifestSha256())
                || !isSha256(envelope.configurationMetadataSha256())
                || envelope.signature() == null || envelope.signature().length() > 256) {
            throw new IllegalArgumentException("component catalog trust envelope is invalid");
        }
    }

    private void validateRuntimeManifest(byte[] bytes, String bundleId, String version)
            throws IOException {
        JsonNode manifest = json.readTree(bytes);
        if (!manifest.isObject() || manifest.path("schemaVersion").asInt(-1) != 1
                || !bundleId.equals(manifest.path("id").asText(null))
                || !version.equals(manifest.path("version").asText(null))
                || !manifest.path("contributions").isArray()
                || manifest.path("contributions").isEmpty()
                || manifest.path("contributions").size() > 256) {
            throw new IllegalArgumentException(
                    "runtime manifest must match the component catalog bundle and version");
        }
    }

    private static void validateTrustKeys(Map<String, String> trustKeys) {
        if (trustKeys == null || trustKeys.isEmpty() || trustKeys.size() > 32) {
            throw new IllegalArgumentException("1-32 trusted component publisher keys are required");
        }
        trustKeys.forEach((id, key) -> {
            if (id == null || !KEY_ID.matcher(id).matches()
                    || key == null || !key.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("trusted component publisher key is invalid");
            }
        });
    }

    private static void requireUnique(List<Loaded> catalogs) {
        Set<String> ids = new java.util.LinkedHashSet<>();
        Set<String> bundles = new java.util.LinkedHashSet<>();
        for (Loaded loaded : catalogs) {
            if (!ids.add(loaded.catalog().catalogId())
                    || !bundles.add(loaded.catalog().bundleId())) {
                throw new IllegalArgumentException("component catalog or bundle id collides");
            }
        }
    }

    private static Path safeRelative(String path) {
        if (path == null || path.isBlank() || path.length() > 512
                || path.contains("\\") || path.startsWith("/") || path.contains("\u0000")
                || !path.matches("component-catalogs/[a-z][a-z0-9.-]{0,127}\\.json")) {
            throw new IllegalArgumentException("component catalog path is invalid");
        }
        Path relative = Path.of(path).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")
                || !relative.toString().startsWith("component-catalogs/")) {
            throw new IllegalArgumentException(
                    "component catalog must be under component-catalogs/");
        }
        return relative;
    }

    private static void requireArtifact(Path artifact) throws IOException {
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(artifact)) {
            throw new IOException("plugin artifact must be a regular, non-symlink jar or zip");
        }
        String name = artifact.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".jar") && !name.endsWith(".zip")) {
            throw new IOException("plugin artifact must be a jar or zip archive");
        }
    }

    private static byte[] readSeed(Path path) throws IOException {
        byte[] bytes = boundedFile(path, 256, "publisher seed file");
        String encoded = new String(bytes, StandardCharsets.US_ASCII).trim();
        if (!encoded.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "publisher seed file must contain exactly 64 hexadecimal characters");
        }
        return HexFormat.of().parseHex(encoded);
    }

    private static byte[] decode(String encoded, String label) throws IOException {
        if (encoded == null || encoded.length() > 2 * MAX_ENTRY_BYTES) {
            throw new IOException(label + " encoding exceeds size limit");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > MAX_ENTRY_BYTES) throw new IOException(label + " exceeds size limit");
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw new IOException(label + " encoding is invalid");
        }
    }

    private static byte[] boundedFile(Path path, int limit, String label) throws IOException {
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path) || Files.size(path) > limit) {
            throw new IOException(label + " is missing, unsafe, or exceeds size limit");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > limit) {
            throw new IOException(label + " is empty or exceeds size limit");
        }
        return bytes;
    }

    private static String sha256File(Path path, int limit, String label) throws IOException {
        if (Files.size(path) > limit) throw new IOException(label + " exceeds size limit");
        try (InputStream input = Files.newInputStream(path)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ZipEntry unique(ZipFile archive, String name) throws IOException {
        ZipEntry found = uniqueOptional(archive, name);
        if (found == null) throw new IOException("plugin archive is missing " + name);
        return found;
    }

    private static ZipEntry uniqueOptional(ZipFile archive, String name) throws IOException {
        ZipEntry found = null;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && name.equals(entry.getName())) {
                if (found != null) throw new IOException("plugin archive contains duplicate " + name);
                found = entry;
            }
        }
        return found;
    }

    private static ZipEntry uniqueRuntimeManifest(ZipFile archive, String expected)
            throws IOException {
        ZipEntry found = null;
        int count = 0;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(MANIFEST_PREFIX)
                    && entry.getName().endsWith(".json")) {
                count++;
                if (expected.equals(entry.getName())) found = entry;
            }
        }
        if (count != 1 || found == null) {
            throw new IOException(
                    "plugin archive must contain exactly one matching runtime manifest");
        }
        return found;
    }

    private static byte[] readBounded(ZipFile archive, ZipEntry entry, String label)
            throws IOException {
        if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException(label + " exceeds size limit");
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_ENTRY_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_ENTRY_BYTES) {
                throw new IOException(label + " is empty or exceeds size limit");
            }
            return bytes;
        }
    }

    private static void requireDigest(String label, String expected, String actual) {
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException(label + " digest does not match signed catalog");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static Path projectRoot(Path project) {
        Path root = project.toAbsolutePath().normalize();
        return root.getFileName() != null
                && AppChainProjectRenderer.BLUEPRINT_FILE.equals(root.getFileName().toString())
                ? root.getParent() : root;
    }

    record Catalog(
            String schemaVersion,
            String catalogId,
            String bundleId,
            String bundleVersion,
            AppChainProjectModel.Artifact artifact,
            List<AppChainProjectModel.Capability> capabilities) {
    }

    record TrustEnvelope(
            int schemaVersion,
            String algorithm,
            String keyId,
            String bundleId,
            String bundleVersion,
            String catalogSha256,
            String runtimeManifestSha256,
            String configurationMetadataSha256,
            String signature) {
    }

    record Snapshot(
            int schemaVersion,
            String kind,
            String artifactFileName,
            String artifactSha256,
            String catalogBase64,
            String runtimeManifestBase64,
            String configurationMetadataBase64,
            TrustEnvelope trust) {
    }

    record Loaded(
            Catalog catalog,
            TrustEnvelope trust,
            Snapshot snapshot,
            byte[] snapshotBytes,
            String snapshotSha256,
            String catalogSha256,
            String runtimeManifestSha256,
            String configurationMetadataSha256,
            byte[] configurationMetadataBytes) {
        Loaded {
            snapshotBytes = snapshotBytes.clone();
            configurationMetadataBytes = configurationMetadataBytes.clone();
        }

        @Override public byte[] snapshotBytes() {
            return snapshotBytes.clone();
        }

        @Override public byte[] configurationMetadataBytes() {
            return configurationMetadataBytes.clone();
        }

        String inputPath() {
            return "component-catalogs/" + catalog.catalogId() + ".json";
        }

    }

    record SignedCatalog(
            TrustEnvelope envelope,
            String publicKeyHex,
            byte[] envelopeBytes) {
        SignedCatalog {
            envelopeBytes = envelopeBytes.clone();
        }

        @Override public byte[] envelopeBytes() {
            return envelopeBytes.clone();
        }
    }
}
