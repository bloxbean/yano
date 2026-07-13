package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.zeroj.api.VerificationKeyRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-006 E7.1 / OQ#4: circuits are chain config; the VK is hash-pinned and
 * loaded fail-closed at startup.
 */
class ConfigVkRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsCircuit_whenHashMatches() throws Exception {
        Path vk = tempDir.resolve("demo.vk");
        byte[] vkBytes = "fake-verification-key-bytes".getBytes();
        Files.write(vk, vkBytes);
        String hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(vkBytes));

        ConfigVkRegistry registry = new ConfigVkRegistry(circuit(vk.toString(), hash));
        assertThat(registry.circuitIds()).containsExactly("demo");
        assertThat(registry.lookup(new VerificationKeyRef.ById("demo"))).isPresent();
        assertThat(registry.lookup(new VerificationKeyRef.ById("nope"))).isEmpty();
    }

    @Test
    void failsClosed_whenHashMismatches() throws Exception {
        Path vk = tempDir.resolve("demo.vk");
        Files.write(vk, "real-bytes".getBytes());
        String wrongHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256("other-bytes".getBytes()));

        assertThatThrownBy(() -> new ConfigVkRegistry(circuit(vk.toString(), wrongHash)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VK hash mismatch")
                .hasMessageContaining("demo");
    }

    @Test
    void failsFast_whenVkFileMissing() {
        Map<String, String> settings = circuit(tempDir.resolve("nope.vk").toString(), "00");
        assertThatThrownBy(() -> new ConfigVkRegistry(settings))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read VK file");
    }

    private static Map<String, String> circuit(String vkFile, String hash) {
        Map<String, String> settings = new HashMap<>();
        settings.put("zk.circuits[0].id", "demo");
        settings.put("zk.circuits[0].vk-file", vkFile);
        settings.put("zk.circuits[0].vk-hash", hash);
        settings.put("zk.circuits[0].proof-system", "groth16");
        settings.put("zk.circuits[0].curve", "bls12381");
        return settings;
    }
}
