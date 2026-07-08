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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E2.1/E2.2: standard-library machines drive a real (single-member,
 * self-proposing) sequenced chain end-to-end — commands finalize, business
 * rules are enforced deterministically, and every entry is provable.
 */
@Timeout(90)
class StdlibStateMachinesTest {

    private static final Logger log = LoggerFactory.getLogger(StdlibStateMachinesTest.class);
    private static final byte[] KEY_A = seed(61);

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
    void kvRegistry_ownershipEnforced_andProvable() throws Exception {
        AppChainSubsystem node = startNode("kv", KvRegistryStateMachine.ID);

        byte[] key = "asset/policy-1".getBytes(StandardCharsets.UTF_8);
        node.submit("registry", KvRegistryStateMachine.put(key, "meta-v1".getBytes(StandardCharsets.UTF_8)));
        awaitTrue("first put finalized", () -> node.stateValue(key).isPresent());

        byte[] entry = node.stateValue(key).orElseThrow();
        assertThat(KvRegistryStateMachine.decodeValue(entry)).isEqualTo("meta-v1".getBytes(StandardCharsets.UTF_8));
        assertThat(KvRegistryStateMachine.decodeOwner(entry))
                .isEqualTo(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));

        // Owner updates work (same member is the only sender here)
        node.submit("registry", KvRegistryStateMachine.put(key, "meta-v2".getBytes(StandardCharsets.UTF_8)));
        awaitTrue("update finalized", () -> Arrays.equals(
                KvRegistryStateMachine.decodeValue(node.stateValue(key).orElseThrow()),
                "meta-v2".getBytes(StandardCharsets.UTF_8)));

        // Provable
        assertThat(node.stateProof(key)).isPresent();

        // Malformed commands are rejected at admission (never finalize)
        long tip = node.tipHeight();
        node.submit("registry", "garbage".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(2000);
        assertThat(node.tipHeight()).isEqualTo(tip); // nothing new finalized

        // Delete
        node.submit("registry", KvRegistryStateMachine.delete(key));
        awaitTrue("delete finalized", () -> node.stateValue(key).isEmpty());
    }

    @Test
    void approvals_kOfN_lifecycle() throws Exception {
        AppChainSubsystem node = startNode("appr", ApprovalsStateMachine.ID);

        // Propose requiring 1 approval (single-member chain), no deadline
        node.submit("wf", ApprovalsStateMachine.propose(
                "release-42", "deploy v42".getBytes(StandardCharsets.UTF_8), 1, 0));
        byte[] itemKey = ApprovalsStateMachine.itemKey("release-42");
        awaitTrue("proposed", () -> node.stateValue(itemKey).isPresent());
        assertThat(ApprovalsStateMachine.decodeItem(node.stateValue(itemKey).orElseThrow()).status())
                .isEqualTo(ApprovalsStateMachine.STATUS_PENDING);

        // Approve → threshold reached → APPROVED (terminal)
        node.submit("wf", ApprovalsStateMachine.approve("release-42"));
        awaitTrue("approved", () -> ApprovalsStateMachine
                .decodeItem(node.stateValue(itemKey).orElseThrow()).status()
                == ApprovalsStateMachine.STATUS_APPROVED);

        // Terminal: a later reject is a no-op
        node.submit("wf", ApprovalsStateMachine.reject("release-42"));
        Thread.sleep(2000);
        ApprovalsStateMachine.Item item = ApprovalsStateMachine.decodeItem(node.stateValue(itemKey).orElseThrow());
        assertThat(item.status()).isEqualTo(ApprovalsStateMachine.STATUS_APPROVED);
        assertThat(item.approvers()).hasSize(1);

        // Reject path on a second item
        node.submit("wf", ApprovalsStateMachine.propose(
                "release-43", "deploy v43".getBytes(StandardCharsets.UTF_8), 1, 0));
        byte[] item43 = ApprovalsStateMachine.itemKey("release-43");
        awaitTrue("43 proposed", () -> node.stateValue(item43).isPresent());
        node.submit("wf", ApprovalsStateMachine.reject("release-43"));
        awaitTrue("43 rejected", () -> ApprovalsStateMachine
                .decodeItem(node.stateValue(item43).orElseThrow()).status()
                == ApprovalsStateMachine.STATUS_REJECTED);

        // Deadline expiry: propose with a deadline in the past, then touch it
        node.submit("wf", ApprovalsStateMachine.propose(
                "release-44", "deploy v44".getBytes(StandardCharsets.UTF_8), 1,
                System.currentTimeMillis() - 60_000));
        byte[] item44 = ApprovalsStateMachine.itemKey("release-44");
        awaitTrue("44 proposed", () -> node.stateValue(item44).isPresent());
        node.submit("wf", ApprovalsStateMachine.approve("release-44"));
        awaitTrue("44 expired (deadline passed before approval)", () -> ApprovalsStateMachine
                .decodeItem(node.stateValue(item44).orElseThrow()).status()
                == ApprovalsStateMachine.STATUS_EXPIRED);

        // Everything provable
        assertThat(node.stateProof(itemKey)).isPresent();
    }

    private AppChainSubsystem startNode(String name, String stateMachineId) {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("stdlib-" + name)
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)   // self-proposing single member
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
