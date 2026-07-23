package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainMetadataDescriptor;
import com.bloxbean.cardano.yano.appchain.config.ValidationCoverage;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies vendor identity and descriptor/runtime-manifest binding without loading plugin code. */
final class AppChainMetadataTrustVerifier {
    static final String SIGNATURE_PATH =
            "META-INF/yano/appchain-config-metadata-v1.sig.json";
    private static final String MANIFEST_PREFIX = "META-INF/yano/plugins/";
    private static final int MAX_ENTRY_BYTES = 1_048_576;
    private static final int MAX_ARCHIVE_ENTRIES = 50_000;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of()
            .parseHex("302a300506032b6570032100");

    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    AppChainProjectModel.MetadataTrustResult verify(Path artifact, Map<String, String> trustKeys)
            throws IOException {
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(artifact)) {
            throw new IOException("plugin artifact must be a regular, non-symlink jar or zip");
        }
        String fileName = artifact.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!fileName.endsWith(".jar") && !fileName.endsWith(".zip")) {
            throw new IOException("plugin artifact must be a jar or zip archive");
        }
        if (trustKeys == null || trustKeys.isEmpty()) {
            throw new IllegalArgumentException("at least one --trust-key is required");
        }
        if (trustKeys.size() > 32) {
            throw new IllegalArgumentException("at most 32 trusted metadata keys are accepted");
        }
        trustKeys.forEach((id, key) -> {
            if (id == null || !KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("trusted metadata key id is invalid");
            }
            parseRawPublicKey(key);
        });

        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            if (archive.size() > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("plugin archive contains too many entries");
            }
            ZipEntry descriptorEntry = unique(archive, AppChainMetadataDescriptor.RESOURCE_PATH);
            ZipEntry signatureEntry = unique(archive, SIGNATURE_PATH);
            byte[] descriptorBytes = readBounded(archive, descriptorEntry, "metadata descriptor");
            byte[] signatureBytes = readBounded(archive, signatureEntry, "metadata signature");
            AppChainMetadataDescriptor descriptor = json.readValue(
                    descriptorBytes, AppChainMetadataDescriptor.class);
            AppChainDescriptorLoader.validateUntrustedMetadata(descriptor);
            TrustEnvelope envelope = json.readValue(signatureBytes, TrustEnvelope.class);
            validateEnvelope(envelope);

            String manifestPath = MANIFEST_PREFIX + envelope.bundleId() + ".json";
            byte[] manifestBytes = readBounded(
                    archive, uniqueRuntimeManifest(archive, manifestPath), "runtime manifest");
            validateRuntimeManifest(manifestBytes, envelope.bundleId());

            String descriptorDigest = AppChainProjectCatalog.sha256(descriptorBytes);
            String manifestDigest = AppChainProjectCatalog.sha256(manifestBytes);
            requireDigest("descriptor", envelope.descriptorSha256(), descriptorDigest);
            requireDigest("runtime manifest", envelope.runtimeManifestSha256(), manifestDigest);
            String keyHex = trustKeys.get(envelope.keyId());
            if (keyHex == null) {
                throw new IllegalArgumentException(
                        "metadata signature key is not trusted: " + envelope.keyId());
            }
            verifySignature(envelope, descriptor.id(), keyHex);
            return new AppChainProjectModel.MetadataTrustResult(
                    "TRUSTED_METADATA", 1, "Ed25519", envelope.keyId(),
                    envelope.bundleId(), descriptor.id(), descriptorDigest, manifestDigest,
                    ValidationCoverage.PARTIAL.name());
        }
    }

    private void validateRuntimeManifest(byte[] bytes, String bundleId) throws IOException {
        JsonNode manifest = json.readTree(bytes);
        if (!manifest.isObject()
                || manifest.path("schemaVersion").asInt(-1) != 1
                || !bundleId.equals(manifest.path("id").asText(null))
                || !manifest.path("contributions").isArray()
                || manifest.path("contributions").isEmpty()
                || manifest.path("contributions").size() > 256) {
            throw new IllegalArgumentException(
                    "runtime manifest must be schema v1, match bundleId, and declare contributions");
        }
    }

    private static void validateEnvelope(TrustEnvelope envelope) {
        if (envelope.schemaVersion() != 1
                || !"Ed25519".equals(envelope.algorithm())
                || envelope.keyId() == null || !KEY_ID.matcher(envelope.keyId()).matches()
                || envelope.bundleId() == null
                || !envelope.bundleId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
                || !isSha256(envelope.descriptorSha256())
                || !isSha256(envelope.runtimeManifestSha256())
                || envelope.signature() == null || envelope.signature().length() > 256) {
            throw new IllegalArgumentException("metadata trust envelope is invalid");
        }
    }

    private static void verifySignature(
            TrustEnvelope envelope, String descriptorId, String rawPublicKeyHex) {
        try {
            byte[] rawKey = parseRawPublicKey(rawPublicKeyHex);
            byte[] encoded = new byte[ED25519_X509_PREFIX.length + rawKey.length];
            System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(rawKey, 0, encoded, ED25519_X509_PREFIX.length, rawKey.length);
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            byte[] signatureBytes;
            try {
                signatureBytes = Base64.getDecoder().decode(envelope.signature());
            } catch (IllegalArgumentException invalidBase64) {
                throw new IllegalArgumentException("metadata signature is not valid base64");
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonicalPayload(envelope, descriptorId));
            if (!verifier.verify(signatureBytes)) {
                throw new IllegalArgumentException("metadata signature verification failed");
            }
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Ed25519 verification is unavailable", failure);
        }
    }

    static byte[] canonicalPayload(TrustEnvelope envelope, String descriptorId) {
        return ("yano-appchain-config-metadata-trust-v1\n"
                + descriptorId + "\n" + envelope.bundleId() + "\n"
                + envelope.keyId() + "\n" + envelope.descriptorSha256() + "\n"
                + envelope.runtimeManifestSha256() + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] parseRawPublicKey(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("trusted Ed25519 public key must be 64 hex characters");
        }
        return HexFormat.of().parseHex(value);
    }

    private static ZipEntry unique(ZipFile archive, String name) throws IOException {
        ZipEntry found = null;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (name.equals(entry.getName()) && !entry.isDirectory()) {
                if (found != null) throw new IOException("plugin archive contains duplicate " + name);
                found = entry;
            }
        }
        if (found == null) throw new IOException("plugin archive is missing " + name);
        return found;
    }

    private static ZipEntry uniqueRuntimeManifest(ZipFile archive, String expected)
            throws IOException {
        ZipEntry found = null;
        int count = 0;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(MANIFEST_PREFIX)
                    && name.endsWith(".json")) {
                count++;
                if (expected.equals(name)) found = entry;
            }
        }
        if (count != 1 || found == null) {
            throw new IOException(
                    "plugin archive must contain exactly one runtime manifest matching bundleId");
        }
        return found;
    }

    private static byte[] readBounded(ZipFile archive, ZipEntry entry, String label)
            throws IOException {
        if (entry.getSize() > MAX_ENTRY_BYTES) {
            throw new IOException(label + " exceeds size limit");
        }
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_ENTRY_BYTES + 1);
            if (bytes.length > MAX_ENTRY_BYTES) {
                throw new IOException(label + " exceeds size limit");
            }
            return bytes;
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void requireDigest(String label, String declared, String actual) {
        if (!MessageDigest.isEqual(declared.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException(label + " digest does not match signed metadata");
        }
    }

    record TrustEnvelope(
            int schemaVersion,
            String algorithm,
            String keyId,
            String bundleId,
            String descriptorSha256,
            String runtimeManifestSha256,
            String signature) {
    }
}
