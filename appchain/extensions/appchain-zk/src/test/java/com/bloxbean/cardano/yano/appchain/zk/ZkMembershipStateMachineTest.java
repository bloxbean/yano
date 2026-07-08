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
 * ADR-006 E7.3: anonymous-but-authorized submissions — a valid membership proof
 * is admitted, the per-nullifier dedup (applied in apply() against MPF state)
 * prevents a second action under the same nullifier, and an invalid proof is
 * rejected. Driven through a real chain with a stub verifier backend.
 */
@Timeout(60)
class ZkMembershipStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(ZkMembershipStateMachineTest.class);
    private static final byte[] KEY_A = seed(181);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void anonymousSubmission_admittedOnce_perNullifier() throws Exception {
        Path vk = tempDir.resolve("membership.vk");
        byte[] vkBytes = "membership-vk".getBytes();
        Files.write(vk, vkBytes);
        String hash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(vkBytes));
        Map<String, String> settings = Map.of(
                "zk.circuits[0].id", "membership",
                "zk.circuits[0].vk-file", vk.toString(),
                "zk.circuits[0].vk-hash", hash,
                "zk.circuits[0].proof-system", "groth16",
                "zk.circuits[0].curve", "bls12381");

        ConfigVkRegistry vkRegistry = new ConfigVkRegistry(settings);
        VerifierRegistry verifierRegistry = VerifierRegistry.empty();
        verifierRegistry.register(new StubZkVerifier());
        ZkVerificationService service = new ZkVerificationService(vkRegistry, verifierRegistry);
        ZkMembershipStateMachine machine = new ZkMembershipStateMachine(service);

        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("anon-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, machine,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        byte[] nullifier = Blake2bUtil.blake2bHash256("member-secret|vote-round-1".getBytes());
        byte[] context = "vote-round-1".getBytes(StandardCharsets.UTF_8);

        // Valid anonymous vote → admitted, nullifier consumed, payload recorded
        byte[] vote1 = membershipBody("VALID", nullifier, context, "yes".getBytes());
        String id1 = node.submit("votes", vote1);
        awaitTrue("first anonymous vote finalized",
                () -> node.stateValue(ZkMembershipStateMachine.nullifierKey(nullifier)).isPresent());
        assertThat(node.stateValue(ZkMembershipStateMachine.recordKey(nullifier))).isPresent();
        assertThat(node.stateProof(ZkMembershipStateMachine.recordKey(nullifier))).isPresent();

        // Second action under the SAME nullifier → admitted to the pool but a
        // deterministic no-op in apply (double-action prevented). The recorded
        // payload stays the first vote.
        byte[] vote2 = membershipBody("VALID", nullifier, context, "no".getBytes());
        node.submit("votes", vote2);
        Thread.sleep(2000);
        byte[] recorded = node.stateValue(ZkMembershipStateMachine.recordKey(nullifier)).orElseThrow();
        assertThat(new String(recorded, StandardCharsets.UTF_8)).contains("yes").doesNotContain("no");

        // A DIFFERENT nullifier (different member/context) is a distinct action
        byte[] nullifier2 = Blake2bUtil.blake2bHash256("other-member|vote-round-1".getBytes());
        byte[] vote3 = membershipBody("VALID", nullifier2, context, "yes".getBytes());
        node.submit("votes", vote3);
        awaitTrue("second distinct nullifier recorded",
                () -> node.stateValue(ZkMembershipStateMachine.recordKey(nullifier2)).isPresent());

        // Invalid membership proof → rejected at admission
        long tip = node.tipHeight();
        byte[] forged = membershipBody("FORGED", Blake2bUtil.blake2bHash256("x".getBytes()), context, "yes".getBytes());
        node.submit("votes", forged);
        Thread.sleep(1500);
        assertThat(node.tipHeight()).isEqualTo(tip);
    }

    private static byte[] membershipBody(String proof, byte[] nullifier, byte[] context, byte[] payload) {
        // The nullifier must be bound to the proof (a public input) — the circuit
        // exposes it, so replaying a proof with a different nullifier is rejected.
        return new MembershipProofBody("membership", "groth16", "bls12381",
                proof.getBytes(StandardCharsets.UTF_8),
                List.of(new java.math.BigInteger(1, nullifier)), nullifier, context, payload).encode();
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
