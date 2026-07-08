package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import com.bloxbean.cardano.zeroj.verifier.core.VerifierRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E7.1: the ZK gate verifies an in-body proof at admission (and
 * re-verifies deterministically in apply()), admitting valid proofs and
 * rejecting invalid ones — driven through a real single-node sequenced chain
 * with a stub verifier backend (no trusted setup / real crypto needed).
 */
@Timeout(60)
class ZkGateStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(ZkGateStateMachineTest.class);
    private static final byte[] KEY_A = seed(161);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void gate_admitsValidProof_rejectsInvalidAndNonProof() throws Exception {
        // Config-pinned VK for circuit "demo"
        Path vk = tempDir.resolve("demo.vk");
        byte[] vkBytes = "demo-vk".getBytes();
        Files.write(vk, vkBytes);
        String hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(vkBytes));
        Map<String, String> settings = Map.of(
                "zk.circuits[0].id", "demo",
                "zk.circuits[0].vk-file", vk.toString(),
                "zk.circuits[0].vk-hash", hash,
                "zk.circuits[0].proof-system", "groth16",
                "zk.circuits[0].curve", "bls12381");

        ConfigVkRegistry vkRegistry = new ConfigVkRegistry(settings);
        VerifierRegistry verifierRegistry = VerifierRegistry.empty();
        verifierRegistry.register(new StubZkVerifier());
        ZkVerificationService service = new ZkVerificationService(vkRegistry, verifierRegistry);
        ZkGateStateMachine machine = new ZkGateStateMachine(service);

        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("zk-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        // Library mode: inject the configured machine instance directly
        node = new AppChainSubsystem(config, 42, null, machine,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // Valid proof → admitted, finalized, recorded, provable
        byte[] validBody = new ZkProofBody("demo", "groth16", "bls12381",
                "VALID".getBytes(StandardCharsets.UTF_8), List.of(BigInteger.valueOf(18))).encode();
        String id = node.submit("kyc", validBody);
        awaitTrue("valid proof finalized", () -> node.messageHeight(HexUtil.decodeHexString(id)).isPresent());
        assertThat(node.stateProof(HexUtil.decodeHexString(id))).isPresent();

        // Invalid proof → rejected at admission (no new block)
        long tip = node.tipHeight();
        byte[] invalidBody = new ZkProofBody("demo", "groth16", "bls12381",
                "FORGED".getBytes(StandardCharsets.UTF_8), List.of(BigInteger.ONE)).encode();
        node.submit("kyc", invalidBody);
        Thread.sleep(1500);
        assertThat(node.tipHeight()).isEqualTo(tip);

        // Unknown circuit → rejected
        byte[] unknownCircuit = new ZkProofBody("no-such", "groth16", "bls12381",
                "VALID".getBytes(StandardCharsets.UTF_8), List.of()).encode();
        node.submit("kyc", unknownCircuit);
        Thread.sleep(1000);
        assertThat(node.tipHeight()).isEqualTo(tip);

        // Non-proof garbage → rejected
        node.submit("kyc", "not-a-proof".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1000);
        assertThat(node.tipHeight()).isEqualTo(tip);
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
