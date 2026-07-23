package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR app-layer/008.3 §3: chain-governed membership — a change activates only
 * when threshold-many members submit the identical command; guard rails void
 * invalid activations deterministically; a late-joining NEW member derives the
 * full membership history purely from replay; half-approved commands expire.
 */
@Timeout(240)
class GovernedMembershipIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GovernedMembershipIntegrationTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "gov-chain";

    private static final byte[] KEY_A = seed(71); // fixed proposer
    private static final byte[] KEY_B = seed(72);
    private static final byte[] KEY_C = seed(73); // added via governance

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final List<AppChainSubsystem> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainSubsystem node : nodes) {
            try {
                node.stop();
            } catch (Exception ignored) {
            }
        }
        for (NodeServer server : servers) {
            try {
                server.shutdown();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void thresholdApproval_activates_andLateJoinerDerivesHistory() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> genesis = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();

        AppChainSubsystem nodeA = start("a", KEY_A, genesis, portA, List.of(peer(portB)), 600);
        AppChainSubsystem nodeB = start("b", KEY_B, genesis, portB, List.of(peer(portA)), 600);
        awaitTrue("A/B connected", () -> connected(nodeA) && connected(nodeB));

        // ONE member's command changes nothing
        nodeA.addMember(pubC);
        awaitTrue("first command finalized", () -> nodeA.tipHeight() >= 1 && nodeB.tipHeight() >= 1);
        assertThat(nodeA.members()).containsExactlyInAnyOrder(pubA, pubB);
        assertThat(nodeB.members()).containsExactlyInAnyOrder(pubA, pubB);

        // The SECOND identical command reaches the threshold → activates on BOTH
        nodeB.addMember(pubC);
        awaitTrue("add-C activated on both",
                () -> nodeA.members().contains(pubC) && nodeB.members().contains(pubC));
        assertThat(nodeA.members()).hasSize(3);
        assertThat(nodeB.effectiveThreshold()).isEqualTo(2);
        assertThat(nodeA.status())
                .containsEntry("membershipMode", "governed")
                .containsEntry("membershipActiveMembers", 2)
                .containsEntry("membershipActiveThreshold", 2)
                .containsEntry("memberActiveForNextBlock", true)
                .containsKeys("membershipEpochFromHeight", "membershipEpochActive");
        assertThat((long) nodeA.status().get("membershipEpochFromHeight"))
                .isGreaterThan(nodeA.tipHeight());

        // Ordinary traffic still flows after the governance change
        String id = nodeA.submit("t", "post-governance".getBytes(StandardCharsets.UTF_8));
        awaitTrue("post-governance message finalized",
                () -> nodeB.messageHeight(HexUtil.decodeHexString(id)).isPresent());

        // LATE JOINER: C starts fresh with the ORIGINAL genesis list (it is
        // not in it!) and must derive its own membership purely from replay
        AppChainSubsystem nodeC = start("c", KEY_C, genesis, 0, List.of(peer(portA)), 600);
        awaitTrue("C caught up and derived the epochs",
                () -> nodeC.tipHeight() >= nodeA.tipHeight()
                        && nodeC.members().contains(pubC));
        assertThat(nodeC.stateRoot()).isEqualTo(nodeA.stateRoot());
        assertThat(nodeC.members()).containsExactlyInAnyOrderElementsOf(nodeA.members());
    }

    @Test
    void guardRail_invalidThreshold_isVoidDeterministically() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        Set<String> genesis = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();

        AppChainSubsystem nodeA = start("ga", KEY_A, genesis, portA, List.of(peer(portB)), 600);
        AppChainSubsystem nodeB = start("gb", KEY_B, genesis, portB, List.of(peer(portA)), 600);
        awaitTrue("connected", () -> connected(nodeA) && connected(nodeB));

        // threshold 5 > member count → activation is VOID on every node
        nodeA.setThreshold(5);
        nodeB.setThreshold(5);
        awaitTrue("both commands finalized", () -> nodeA.tipHeight() >= 1 && nodeB.tipHeight() >= 1);

        String id = nodeA.submit("t", "still-alive".getBytes(StandardCharsets.UTF_8));
        awaitTrue("chain alive after void activation",
                () -> nodeB.messageHeight(HexUtil.decodeHexString(id)).isPresent());
        assertThat(nodeA.effectiveThreshold()).isEqualTo(2);
        assertThat(nodeB.effectiveThreshold()).isEqualTo(2);
    }

    @Test
    void halfApprovedCommand_expiresAfterWindow() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> genesis = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();

        // Tiny approval window: 2 blocks
        AppChainSubsystem nodeA = start("ea", KEY_A, genesis, portA, List.of(peer(portB)), 2);
        AppChainSubsystem nodeB = start("eb", KEY_B, genesis, portB, List.of(peer(portA)), 2);
        awaitTrue("connected", () -> connected(nodeA) && connected(nodeB));

        nodeA.addMember(pubC); // 1 of 2 approvals
        awaitTrue("first approval finalized", () -> nodeA.tipHeight() >= 1);

        // Let the window pass with ordinary blocks
        for (int i = 0; i < 3; i++) {
            String id = nodeA.submit("t", ("filler-" + i).getBytes(StandardCharsets.UTF_8));
            awaitTrue("filler " + i, () -> nodeA.messageHeight(HexUtil.decodeHexString(id)).isPresent());
        }

        // B's approval comes too late — the count restarted, still 1 of 2
        nodeB.addMember(pubC);
        awaitTrue("late approval finalized", () -> nodeB.tipHeight() >= 5);
        String id = nodeA.submit("t", "check".getBytes(StandardCharsets.UTF_8));
        awaitTrue("chain alive", () -> nodeB.messageHeight(HexUtil.decodeHexString(id)).isPresent());

        assertThat(nodeA.members()).containsExactlyInAnyOrder(pubA, pubB);
        assertThat(nodeB.members()).containsExactlyInAnyOrder(pubA, pubB);
    }

    // ------------------------------------------------------------------

    private AppChainSubsystem start(String name, byte[] key, Set<String> members, int serverPort,
                                    List<AppChainConfig.AppPeer> peers, long approvalWindow)
            throws Exception {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(key))
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(pubHex(KEY_A))
                .threshold(2)
                .blockIntervalMs(500)
                .pluginSettings(Map.of(
                        "membership.mode", "governed",
                        "membership.approval-window-blocks", String.valueOf(approvalWindow)))
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                tempDir.resolve("ledger-" + name).toString(), null, log);
        nodes.add(subsystem);

        if (serverPort > 0) {
            NodeServer server = new NodeServer(serverPort,
                    N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC, false, 0, false),
                    new MinimalChainState(),
                    null, null,
                    subsystem.serverAgentFactories());
            servers.add(server);
            Thread thread = new Thread(server::start);
            thread.setDaemon(true);
            thread.start();
            Thread.sleep(800);
        }
        subsystem.start();
        return subsystem;
    }

    private static boolean connected(AppChainSubsystem subsystem) {
        Object peers = subsystem.status().get("peers");
        return peers instanceof Map<?, ?> peerMap && !peerMap.isEmpty()
                && peerMap.values().stream().allMatch(Boolean.TRUE::equals);
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static AppChainConfig.AppPeer peer(int port) {
        return new AppChainConfig.AppPeer("localhost", port);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }

    private static String pubHex(byte[] privateKey) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(privateKey));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static class MinimalChainState implements ChainState {
        @Override public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {}
        @Override public byte[] getBlock(byte[] blockHash) { return null; }
        @Override public boolean hasBlock(byte[] blockHash) { return false; }
        @Override public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {}
        @Override public byte[] getBlockHeader(byte[] blockHash) { return null; }
        @Override public byte[] getBlockByNumber(Long blockNumber) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long blockNumber) { return null; }
        @Override public Point findNextBlock(Point currentPoint) { return null; }
        @Override public Point findNextBlockHeader(Point currentPoint) { return null; }
        @Override public List<Point> findBlocksInRange(Point from, Point to) { return Collections.emptyList(); }
        @Override public Point findLastPointAfterNBlocks(Point from, long batchSize) { return null; }
        @Override public boolean hasPoint(Point point) { return false; }
        @Override public Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long blockNumber) { return null; }
        @Override public void rollbackTo(Long slot) {}
        @Override public ChainTip getTip() { return null; }
        @Override public ChainTip getHeaderTip() { return null; }
    }
}
