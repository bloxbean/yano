package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.zeroj.bbs.BbsKeyPair;
import com.bloxbean.cardano.zeroj.verifier.core.VerifierRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 Wave-3 review fix: followers never run validate() — they only re-run
 * apply(). These tests drive apply() DIRECTLY (a follower's view) with forged
 * inputs and assert nothing is recorded, proving the security checks are
 * consensus-enforced, not merely proposer-side admission.
 */
class ZkApplyConsensusTest {

    @TempDir
    Path tempDir;

    @Test
    void zkGate_apply_recordsNothingForInvalidProof() throws Exception {
        ZkGateStateMachine machine = new ZkGateStateMachine(stubService());
        MapWriter writer = new MapWriter();

        // A forged proof ("FORGED" != "VALID") must NOT be recorded by a follower
        byte[] forged = new ZkProofBody("demo", "groth16", "bls12381",
                "FORGED".getBytes(StandardCharsets.UTF_8), List.of()).encode();
        machine.apply(block(forged), writer);
        assertThat(writer.map.keySet()).containsOnly("~tip"); // only the tip marker, no message record

        // A valid proof IS recorded
        MapWriter writer2 = new MapWriter();
        byte[] valid = new ZkProofBody("demo", "groth16", "bls12381",
                "VALID".getBytes(StandardCharsets.UTF_8), List.of()).encode();
        machine.apply(block(valid), writer2);
        assertThat(writer2.map).hasSizeGreaterThan(1);
    }

    @Test
    void zkMembership_apply_rejectsUnboundNullifier() {
        ZkMembershipStateMachine machine = new ZkMembershipStateMachine(stubServiceLenient());
        MapWriter writer = new MapWriter();

        byte[] nullifier = Blake2bUtil.blake2bHash256("n".getBytes());
        // publicInputs do NOT contain the nullifier → unbound → apply records nothing
        byte[] unbound = new MembershipProofBody("membership", "groth16", "bls12381",
                "VALID".getBytes(StandardCharsets.UTF_8), List.of(BigInteger.ONE),
                nullifier, "ctx".getBytes(), "vote".getBytes()).encode();
        machine.apply(block(unbound), writer);
        assertThat(writer.map).isEmpty();

        // Bound (nullifier IS a public input) → recorded
        byte[] bound = new MembershipProofBody("membership", "groth16", "bls12381",
                "VALID".getBytes(StandardCharsets.UTF_8), List.of(new BigInteger(1, nullifier)),
                nullifier, "ctx".getBytes(), "vote".getBytes()).encode();
        machine.apply(block(bound), writer);
        assertThat(writer.map.keySet().stream().anyMatch(k -> k.startsWith("~anon/"))).isTrue();
    }

    @Test
    void credentialRegistry_apply_rejectsForgedAndUnknownIssuer() {
        BbsKeyPair issuer = BbsCredentials.issuerKeyPair(seed(210));
        CredentialRegistryStateMachine machine = new CredentialRegistryStateMachine(
                Map.of("hr", issuer.publicKey()));
        MapWriter writer = new MapWriter();

        List<byte[]> attrs = List.of("Alice".getBytes(), "Eng".getBytes());
        CredentialBody valid = BbsCredentials.sign(issuer, "hr", "c1", attrs, new byte[0]);

        // Forged: valid signature but tampered attributes → apply records nothing
        CredentialBody forged = new CredentialBody("hr", "c2", valid.signature(),
                valid.header(), List.of("Mallory".getBytes(), "Eng".getBytes()));
        machine.apply(block(forged.encode()), writer);
        assertThat(writer.map).isEmpty();

        // Unknown issuer → nothing
        CredentialBody unknown = new CredentialBody("finance", "c3", valid.signature(),
                valid.header(), attrs);
        machine.apply(block(unknown.encode()), writer);
        assertThat(writer.map).isEmpty();

        // Valid → recorded
        machine.apply(block(valid.encode()), writer);
        assertThat(writer.map.keySet()).contains("cred/c1");
    }

    // ------------------------------------------------------------------

    private ZkVerificationService stubService() {
        return stubServiceLenient();
    }

    private ZkVerificationService stubServiceLenient() {
        try {
            Path vk = tempDir.resolve("vk-" + System.nanoTime());
            byte[] vkBytes = "vk".getBytes();
            Files.write(vk, vkBytes);
            String hash = com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                    Blake2bUtil.blake2bHash256(vkBytes));
            Map<String, String> settings = new HashMap<>();
            for (String circuit : new String[]{"demo", "membership"}) {
                settings.put("zk.circuits[" + circuit.length() + "].id", circuit);
                settings.put("zk.circuits[" + circuit.length() + "].vk-file", vk.toString());
                settings.put("zk.circuits[" + circuit.length() + "].vk-hash", hash);
                settings.put("zk.circuits[" + circuit.length() + "].proof-system", "groth16");
                settings.put("zk.circuits[" + circuit.length() + "].curve", "bls12381");
            }
            VerifierRegistry registry = VerifierRegistry.empty();
            registry.register(new StubZkVerifier());
            return new ZkVerificationService(new ConfigVkRegistry(settings), registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AppBlock block(byte[] body) {
        AppMessage message = AppMessage.builder()
                .version(2).chainId("t").topic("t").sender(new byte[32])
                .senderSeq(0).expiresAt(0).body(body)
                .messageId(Blake2bUtil.blake2bHash256(body)).build();
        return new AppBlock(2, "t", 1, new byte[32], 0, new byte[0], 0L,
                new byte[32], new byte[32], List.of(message), new byte[32],
                new FinalityCert(0, List.of()));
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) fill);
        return seed;
    }

    /** Minimal in-memory writer standing in for the MPF staged writer. */
    static final class MapWriter implements AppStateWriter {
        final Map<String, byte[]> map = new HashMap<>();

        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(map.get(new String(key, StandardCharsets.UTF_8)));
        }
        @Override public void put(byte[] key, byte[] value) {
            map.put(new String(key, StandardCharsets.UTF_8), value);
        }
        @Override public void delete(byte[] key) {
            map.remove(new String(key, StandardCharsets.UTF_8));
        }
        @Override public byte[] stateRoot() {
            return new byte[32];
        }
    }
}
