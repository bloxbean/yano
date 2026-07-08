package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import com.bloxbean.cardano.zeroj.bbs.BbsKeyPair;
import com.bloxbean.cardano.zeroj.bbs.BbsPublicKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E7.2: end-to-end BBS selective disclosure with real crypto (no
 * trusted setup) — an issuer signs an attribute set, the chain admits and
 * records it only if the issuer signature verifies, and a holder discloses one
 * attribute that a third party verifies against the issuer key.
 */
@Timeout(60)
class BbsCredentialRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(BbsCredentialRegistryTest.class);
    private static final byte[] KEY_A = seed(171);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void issue_record_disclose_verify() throws Exception {
        BbsKeyPair issuer = BbsCredentials.issuerKeyPair(seed32(200));
        BbsPublicKey issuerKey = issuer.publicKey();

        // Attribute set: [name, dept, clearance]
        List<byte[]> attributes = List.of(
                "Alice".getBytes(StandardCharsets.UTF_8),
                "Engineering".getBytes(StandardCharsets.UTF_8),
                "L4".getBytes(StandardCharsets.UTF_8));
        CredentialBody credential = BbsCredentials.sign(issuer, "hr-dept", "emp-42", attributes,
                "cred-header".getBytes(StandardCharsets.UTF_8));

        // Node-side: credential-registry keyed by the issuer public key
        CredentialRegistryStateMachine machine = new CredentialRegistryStateMachine(
                Map.of("hr-dept", issuerKey));

        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("cred-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, machine,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // Valid credential → admitted, recorded, provable
        String id = node.submit("credentials", credential.encode());
        awaitTrue("credential finalized", () -> node.messageHeight(HexUtil.decodeHexString(id)).isPresent());
        assertThat(node.stateValue(CredentialRegistryStateMachine.credentialKey("emp-42"))).isPresent();
        assertThat(node.stateProof(CredentialRegistryStateMachine.credentialKey("emp-42"))).isPresent();

        // Tampered attributes (signature no longer matches) → rejected
        long tip = node.tipHeight();
        CredentialBody tampered = new CredentialBody("hr-dept", "emp-43", credential.signature(),
                credential.header(), List.of("Mallory".getBytes(), "Engineering".getBytes(), "L4".getBytes()));
        node.submit("credentials", tampered.encode());
        Thread.sleep(1500);
        assertThat(node.tipHeight()).isEqualTo(tip);

        // Unknown issuer → rejected
        CredentialBody wrongIssuer = new CredentialBody("finance", "emp-44", credential.signature(),
                credential.header(), attributes);
        node.submit("credentials", wrongIssuer.encode());
        Thread.sleep(1000);
        assertThat(node.tipHeight()).isEqualTo(tip);

        // Holder discloses ONLY the department (index 1); verifier checks it
        byte[] presentation = BbsCredentials.disclose(issuerKey, credential,
                new int[]{1}, "nonce-123".getBytes(StandardCharsets.UTF_8));
        assertThat(BbsCredentials.verifyDisclosure(issuerKey, presentation)).isTrue();

        // A disclosure verified against the WRONG issuer key fails
        BbsPublicKey otherIssuer = BbsCredentials.issuerKeyPair(seed32(201)).publicKey();
        assertThat(BbsCredentials.verifyDisclosure(otherIssuer, presentation)).isFalse();
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

    private static byte[] seed32(int fill) {
        return seed(fill);
    }
}
