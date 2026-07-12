package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Signed snapshot manifest (ADR app-layer/008.1 I1.7): binds a RocksDB
 * checkpoint to a specific certified tip — chain id, height, tip block hash,
 * state root, membership epochs, last confirmed anchor and a sha256 of every
 * file — signed by the snapshotting member's key. Restore verification runs
 * BEFORE the DB is opened (RocksDB mutates files on open); a marker file
 * records success so normal restarts skip re-verification.
 */
final class SnapshotManifest {

    static final String MANIFEST_FILE = "snapshot-manifest.json";
    static final String SIG_FILE = "snapshot-manifest.sig";
    static final String VERIFIED_MARKER = ".manifest-verified";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SnapshotManifest() {
    }

    /** Write the manifest + detached signature into a freshly created snapshot dir. */
    static void write(Path snapshotDir, String chainId, long height, byte[] tipBlockHash,
                      byte[] stateRoot, byte[] memberEpochsBytes, String lastAnchorTx,
                      long lastAnchorToHeight, SignerProvider signer) {
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("version", 1);
            // Ledger format (ADR-010 FX-M2): the CF set this checkpoint carries.
            // A build that lists fewer CFs cannot open this snapshot (RocksDB
            // refuses unlisted families) — this field turns that into an
            // operator-readable diagnostic instead of a bare RocksDB error.
            manifest.put("ledgerFormat", 2);
            manifest.put("columnFamilies", java.util.List.of("default", "app_blocks", "app_meta",
                    "app_msgs", "mpf_nodes", "app_query_index", "app_fx_records", "app_fx_runtime"));
            manifest.put("chainId", chainId);
            manifest.put("height", height);
            manifest.put("blockHash", tipBlockHash != null ? HexUtil.encodeHexString(tipBlockHash) : "");
            manifest.put("stateRoot", stateRoot != null ? HexUtil.encodeHexString(stateRoot) : "");
            manifest.put("memberEpochsHash", HexUtil.encodeHexString(
                    Blake2bUtil.blake2bHash256(memberEpochsBytes != null ? memberEpochsBytes : new byte[0])));
            if (lastAnchorTx != null && !lastAnchorTx.isBlank()) {
                manifest.put("lastAnchorTx", lastAnchorTx);
                manifest.put("lastAnchorToHeight", lastAnchorToHeight);
            }
            manifest.put("files", hashFiles(snapshotDir));
            manifest.put("createdAtMillis", System.currentTimeMillis());
            manifest.put("signerKey", signer.publicKeyHex());

            byte[] manifestBytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            Files.write(snapshotDir.resolve(MANIFEST_FILE), manifestBytes);
            Files.writeString(snapshotDir.resolve(SIG_FILE),
                    HexUtil.encodeHexString(signer.sign(manifestBytes)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write snapshot manifest in " + snapshotDir, e);
        }
    }

    /**
     * Pre-open restore verification: manifest signature (by a trusted member
     * key) and every file hash. No manifest, or an existing verified marker,
     * means nothing to do.
     *
     * @return the parsed manifest when verification JUST ran (the caller then
     *         binds the opened ledger to it), or null when skipped
     * @throws IllegalStateException on any verification failure
     */
    static JsonNode verifyPreOpen(Path ledgerDir, Set<String> trustedKeysHex, Logger log) {
        Path manifestPath = ledgerDir.resolve(MANIFEST_FILE);
        if (!Files.exists(manifestPath) || Files.exists(ledgerDir.resolve(VERIFIED_MARKER))) {
            return null;
        }
        try {
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            JsonNode manifest = MAPPER.readTree(manifestBytes);

            String signerKey = manifest.path("signerKey").asText("").toLowerCase(Locale.ROOT);
            if (!trustedKeysHex.contains(signerKey)) {
                throw new IllegalStateException("Snapshot manifest signer " + signerKey
                        + " is not a trusted member key");
            }
            String sigHex = Files.readString(ledgerDir.resolve(SIG_FILE)).trim();
            if (!AppMessageSigner.verify(HexUtil.decodeHexString(sigHex), manifestBytes,
                    HexUtil.decodeHexString(signerKey))) {
                throw new IllegalStateException("Snapshot manifest signature verification FAILED");
            }

            Map<String, String> expected = new TreeMap<>();
            manifest.path("files").fields().forEachRemaining(
                    field -> expected.put(field.getKey(), field.getValue().asText()));
            Map<String, String> actual = hashFiles(ledgerDir);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Snapshot file hashes do not match the manifest "
                        + "(expected " + expected.size() + " files, found " + actual.size()
                        + ") — corrupt or tampered snapshot");
            }

            Files.writeString(ledgerDir.resolve(VERIFIED_MARKER),
                    "verified " + System.currentTimeMillis());
            log.info("Snapshot manifest VERIFIED: {} files, signer {}, height {}",
                    actual.size(), signerKey, manifest.path("height").asLong());
            return manifest;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Snapshot manifest verification failed in " + ledgerDir, e);
        }
    }

    /**
     * Post-open binding (only right after a verified restore): the opened
     * ledger's tip must be exactly the snapshot the manifest attests.
     */
    static void verifyPostOpen(JsonNode manifest, long tipHeight, byte[] tipBlockHash,
                               byte[] stateRoot, byte[] memberEpochsBytes) {
        long height = manifest.path("height").asLong();
        if (tipHeight != height) {
            throw new IllegalStateException("Restored ledger tip " + tipHeight
                    + " != manifest height " + height);
        }
        if (height == 0) {
            return; // empty-ledger snapshot: nothing more to bind
        }
        String blockHashHex = tipBlockHash != null ? HexUtil.encodeHexString(tipBlockHash) : "";
        if (!blockHashHex.equals(manifest.path("blockHash").asText(""))) {
            throw new IllegalStateException("Restored tip block hash does not match the manifest");
        }
        String stateRootHex = stateRoot != null ? HexUtil.encodeHexString(stateRoot) : "";
        if (!stateRootHex.equals(manifest.path("stateRoot").asText(""))) {
            throw new IllegalStateException("Restored state root does not match the manifest");
        }
        String epochsHash = HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash256(memberEpochsBytes != null ? memberEpochsBytes : new byte[0]));
        if (!epochsHash.equals(manifest.path("memberEpochsHash").asText(""))) {
            throw new IllegalStateException("Restored membership epochs do not match the manifest");
        }
    }

    /** sha256 per file (sorted relative paths), excluding the manifest trio. */
    private static Map<String, String> hashFiles(Path dir) {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                String relative = dir.relativize(file).toString();
                if (MANIFEST_FILE.equals(relative) || SIG_FILE.equals(relative)
                        || VERIFIED_MARKER.equals(relative)) {
                    return;
                }
                hashes.put(relative, sha256Hex(file));
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to hash snapshot files in " + dir, e);
        }
        return hashes;
    }

    private static String sha256Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexUtil.encodeHexString(digest.digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash " + file, e);
        }
    }
}
