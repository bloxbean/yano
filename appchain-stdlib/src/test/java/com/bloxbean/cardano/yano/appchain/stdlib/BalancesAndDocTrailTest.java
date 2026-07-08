package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E2.3/E2.4: balances and doc-trail standard-library machines driving
 * a real single-member self-proposing sequenced chain.
 */
@Timeout(90)
class BalancesAndDocTrailTest {

    private static final Logger log = LoggerFactory.getLogger(BalancesAndDocTrailTest.class);
    private static final byte[] KEY_A = seed(81);

    @TempDir
    Path tempDir;

    private final List<AppChainSubsystem> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainSubsystem node : nodes) {
            try {
                node.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void balances_mintTransfer_nonNegative_provable() throws Exception {
        AppChainSubsystem node = startNode("bal", BalancesStateMachine.ID);
        String self = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));

        // Mint 100 to self
        node.submit("ledger", BalancesStateMachine.mint(self, BigInteger.valueOf(100)));
        awaitTrue("mint finalized", () -> balance(node, self).equals(BigInteger.valueOf(100)));

        // Transfer 30 self -> alice
        node.submit("ledger", BalancesStateMachine.transfer("alice", BigInteger.valueOf(30)));
        awaitTrue("transfer finalized",
                () -> balance(node, self).equals(BigInteger.valueOf(70))
                        && balance(node, "alice").equals(BigInteger.valueOf(30)));

        // Overspend is a no-op — balances never go negative
        long tip = node.tipHeight();
        node.submit("ledger", BalancesStateMachine.transfer("alice", BigInteger.valueOf(1000)));
        // Wait for a block to pass and confirm balances unchanged
        awaitTrue("overspend block produced", () -> node.tipHeight() > tip);
        assertThat(balance(node, self)).isEqualTo(BigInteger.valueOf(70));
        assertThat(balance(node, "alice")).isEqualTo(BigInteger.valueOf(30));

        // Provable
        assertThat(node.stateProof(BalancesStateMachine.accountKey("alice"))).isPresent();

        // Malformed rejected at admission
        long tip2 = node.tipHeight();
        node.submit("ledger", "junk".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1500);
        assertThat(node.tipHeight()).isEqualTo(tip2);
    }

    @Test
    void docTrail_appendOnlyHistory_provableHead() throws Exception {
        AppChainSubsystem node = startNode("doc", DocTrailStateMachine.ID);
        byte[] author = KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A);

        byte[] h1 = hash("doc-v1");
        byte[] h2 = hash("doc-v2");
        node.submit("dpp", DocTrailStateMachine.append("product-42", h1, "ipfs://a"));
        awaitTrue("first entry", () -> entry(node, "product-42").map(e -> e.count() == 1).orElse(false));
        node.submit("dpp", DocTrailStateMachine.append("product-42", h2, "ipfs://b"));
        awaitTrue("second entry", () -> entry(node, "product-42").map(e -> e.count() == 2).orElse(false));

        // Head matches an independent recomputation of the ordered trail
        byte[] expectedHead = DocTrailStateMachine.computeHead(
                List.of(h1, h2), List.of(author, author));
        assertThat(entry(node, "product-42").orElseThrow().headHash()).isEqualTo(expectedHead);

        // A different entity is independent
        node.submit("dpp", DocTrailStateMachine.append("product-99", hash("x"), ""));
        awaitTrue("other entity", () -> entry(node, "product-99").map(e -> e.count() == 1).orElse(false));
        assertThat(entry(node, "product-42").orElseThrow().count()).isEqualTo(2);

        // Provable head
        assertThat(node.stateProof(DocTrailStateMachine.entityKey("product-42"))).isPresent();
    }

    private static BigInteger balance(AppChainSubsystem node, String account) {
        return node.stateValue(BalancesStateMachine.accountKey(account))
                .map(BalancesStateMachine::decodeBalance).orElse(BigInteger.ZERO);
    }

    private static java.util.Optional<DocTrailStateMachine.Entry> entry(AppChainSubsystem node, String id) {
        return node.stateValue(DocTrailStateMachine.entityKey(id)).map(DocTrailStateMachine::decodeEntry);
    }

    private static byte[] hash(String s) {
        return com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(s.getBytes(StandardCharsets.UTF_8));
    }

    private AppChainSubsystem startNode(String name, String stateMachineId) {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("stdlib2-" + name)
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .stateMachineId(stateMachineId)
                .build();
        AppChainSubsystem node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger-" + name).toString(), null, log);
        nodes.add(node);
        node.start();
        return node;
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
