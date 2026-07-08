package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.zeroj.api.CircuitId;
import com.bloxbean.cardano.zeroj.api.CurveId;
import com.bloxbean.cardano.zeroj.api.ProofSystemId;
import com.bloxbean.cardano.zeroj.api.VerificationMaterial;
import com.bloxbean.cardano.zeroj.api.VerificationKeyRef;
import com.bloxbean.cardano.zeroj.backend.spi.VerificationKeyRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verification-key registry built from chain config (ADR app-layer/006 E7.1,
 * OQ#4). Circuits are chain configuration: each
 * {@code zk.circuits[i].{id,vk-file,vk-hash,proof-system,curve}} pins a
 * {@code circuitId → VK hash}. At construction the VK bytes are loaded and their
 * blake2b-256 hash is compared to the pinned hash — a mismatch is a config
 * error surfaced at startup (fail-closed), never a runtime divergence. All
 * members must agree on VKs exactly as they agree on membership.
 * <p>
 * Keyed by {@link VerificationKeyRef.ById}, where the id is the circuitId.
 */
final class ConfigVkRegistry implements VerificationKeyRegistry {

    private final Map<String, VerificationMaterial> byCircuit = new LinkedHashMap<>();

    /**
     * @param settings chain plugin settings (suffix-keyed, e.g. {@code zk.circuits[0].id})
     */
    ConfigVkRegistry(Map<String, String> settings) {
        for (int i = 0; i < 100; i++) {
            String base = "zk.circuits[" + i + "].";
            String id = settings.get(base + "id");
            if (id == null || id.isBlank()) {
                break;
            }
            String vkFile = require(settings, base + "vk-file", id);
            String pinnedHashHex = require(settings, base + "vk-hash", id);
            ProofSystemId proofSystem = ProofSystemId.fromValue(require(settings, base + "proof-system", id));
            CurveId curve = CurveId.fromValue(require(settings, base + "curve", id));

            byte[] vkBytes = readVk(vkFile, id);
            byte[] actualHash = Blake2bUtil.blake2bHash256(vkBytes);
            byte[] pinnedHash = HexUtil.decodeHexString(pinnedHashHex.trim());
            if (!java.util.Arrays.equals(actualHash, pinnedHash)) {
                throw new IllegalStateException("VK hash mismatch for circuit '" + id + "': config pins "
                        + pinnedHashHex + " but " + vkFile + " hashes to " + HexUtil.encodeHexString(actualHash)
                        + " — refusing to start (a VK mismatch is a config error, not a runtime divergence)");
            }
            byCircuit.put(id, new VerificationMaterial(vkBytes, proofSystem, curve,
                    new CircuitId(id), Optional.of(actualHash)));
        }
    }

    /** Circuit ids this registry knows about. */
    List<String> circuitIds() {
        return List.copyOf(byCircuit.keySet());
    }

    boolean isEmpty() {
        return byCircuit.isEmpty();
    }

    @Override
    public Optional<VerificationMaterial> lookup(VerificationKeyRef ref) {
        if (ref instanceof VerificationKeyRef.ById byId) {
            return Optional.ofNullable(byCircuit.get(byId.id()));
        }
        return Optional.empty();
    }

    @Override
    public void register(VerificationMaterial material) {
        byCircuit.put(material.circuitId().value(), material);
    }

    private static String require(Map<String, String> settings, String key, String circuitId) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required ZK config '" + key
                    + "' for circuit '" + circuitId + "'");
        }
        return value;
    }

    private static byte[] readVk(String vkFile, String circuitId) {
        try {
            return Files.readAllBytes(Path.of(vkFile));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read VK file '" + vkFile
                    + "' for circuit '" + circuitId + "': " + e.getMessage(), e);
        }
    }
}
