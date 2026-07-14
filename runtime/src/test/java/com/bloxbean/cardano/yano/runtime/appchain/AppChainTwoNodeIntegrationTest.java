package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.ReceivedAppMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 exit-criteria test (ADR app-layer/005): two Yano app-chain subsystems with
 * real NodeServers exchange authenticated messages bidirectionally for a shared
 * chain-id; a non-member's messages are rejected.
 */
@Timeout(90)
class AppChainTwoNodeIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainTwoNodeIntegrationTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "it-app-chain";

    private static final byte[] KEY_A = seed(1);
    private static final byte[] KEY_B = seed(2);
    private static final byte[] KEY_C = seed(3); // NOT a member of the group

    private final List<NodeServer> servers = new ArrayList<>();
    private final List<AppChainSubsystem> subsystems = new ArrayList<>();
    private final List<Thread> serverThreads = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainSubsystem subsystem : subsystems) {
            try {
                subsystem.stop();
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
    void twoNodes_exchangeAuthenticatedMessages_bothDirections() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        Set<String> members = Set.of(pubA, pubB);

        int portA = freePort();
        int portB = freePort();

        AppChainSubsystem nodeA = startNode(KEY_A, members, portA, List.of(peer(portB)));

        // Regression: TCPNodeClient reports "running" while its connection is
        // only retrying, and Yaci resets protocol agents once TCP eventually
        // connects. A message submitted before B's server exists must stay in
        // AppPeerClient's replay queue until protocol-100 MsgInitAck, rather
        // than being placed in the agent and erased by that reset.
        String earlyIdFromA = nodeA.submit(
                "orders", "queued before B starts".getBytes(StandardCharsets.UTF_8));

        AppChainSubsystem nodeB = startNode(KEY_B, members, portB, List.of(peer(portA)));

        // Wait for both nodes to connect to each other's server
        awaitTrue("A connected to B", () -> allPeersConnected(nodeA));
        awaitTrue("B connected to A", () -> allPeersConnected(nodeB));
        awaitTrue("B received A's pre-connect message",
                () -> hasPeerMessage(nodeB, earlyIdFromA));
        assertThat(nodeB.recentMessages(0).stream()
                .filter(message -> message.messageIdHex().equals(earlyIdFromA)))
                .hasSize(1);

        // A -> B
        String idFromA = nodeA.submit("orders", "hello from A".getBytes(StandardCharsets.UTF_8));
        awaitTrue("B received A's message", () -> hasPeerMessage(nodeB, idFromA));

        ReceivedAppMessage onB = find(nodeB, idFromA);
        assertThat(onB.chainId()).isEqualTo(CHAIN_ID);
        assertThat(onB.topic()).isEqualTo("orders");
        assertThat(onB.senderHex()).isEqualTo(pubA);
        assertThat(new String(onB.body(), StandardCharsets.UTF_8)).isEqualTo("hello from A");
        assertThat(onB.source()).isEqualTo(ReceivedAppMessage.Source.PEER);

        // B -> A
        String idFromB = nodeB.submit("orders", "hello from B".getBytes(StandardCharsets.UTF_8));
        awaitTrue("A received B's message", () -> hasPeerMessage(nodeA, idFromB));

        ReceivedAppMessage onA = find(nodeA, idFromB);
        assertThat(onA.senderHex()).isEqualTo(pubB);
        assertThat(new String(onA.body(), StandardCharsets.UTF_8)).isEqualTo("hello from B");

        // Multiple messages keep flowing and stay ordered per sender
        String id2 = nodeA.submit("orders", "second from A".getBytes(StandardCharsets.UTF_8));
        String id3 = nodeA.submit("", "third from A".getBytes(StandardCharsets.UTF_8));
        awaitTrue("B received A's later messages",
                () -> hasPeerMessage(nodeB, id2) && hasPeerMessage(nodeB, id3));
        assertThat(find(nodeB, id2).senderSeq()).isLessThan(find(nodeB, id3).senderSeq());

        log.info("Node A status: {}", nodeA.status());
        log.info("Node B status: {}", nodeB.status());
    }

    @Test
    void nonMemberMessages_areRejected() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);

        int portA = freePort();

        // Node A: legitimate group {A, B}
        AppChainSubsystem nodeA = startNode(KEY_A, Set.of(pubA, pubB), portA, List.of());

        // Node C: an outsider that *claims* membership {A, B, C} locally and targets A
        AppChainSubsystem nodeC = startNode(KEY_C, Set.of(pubA, pubB, pubC), 0, List.of(peer(portA)));

        awaitTrue("C connected to A", () -> allPeersConnected(nodeC));

        String idFromC = nodeC.submit("intrusion", "malicious payload".getBytes(StandardCharsets.UTF_8));

        // Give diffusion a chance, then verify A never accepted C's message
        Thread.sleep(5000);
        assertThat(find(nodeA, idFromC)).isNull();
        assertThat(nodeA.recentMessages(100)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Starts an app-chain subsystem and, when serverPort > 0, a NodeServer with
     * the subsystem's app-layer agent factories (as ServeSubsystem does).
     */
    private AppChainSubsystem startNode(byte[] signingKey, Set<String> members,
                                        int serverPort, List<AppChainConfig.AppPeer> peers) throws Exception {
        AppChainConfig config = new AppChainConfig(
                CHAIN_ID,
                HexUtil.encodeHexString(signingKey),
                members,
                peers,
                65536,
                3600,
                600,
                "",   // no sequencer — M1 diffusion-only mode
                1,
                AppChainConfig.DEFAULT_BLOCK_INTERVAL_MS,
                AppChainConfig.DEFAULT_MAX_BLOCK_MESSAGES,
                AppChainConfig.DEFAULT_STATE_MACHINE,
                null,
                null, 0, java.util.List.of(),
                false, 0, java.util.Map.of());
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, log);
        subsystems.add(subsystem);

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
            serverThreads.add(thread);
            Thread.sleep(1000);
        }

        subsystem.start();
        return subsystem;
    }

    private static boolean allPeersConnected(AppChainSubsystem subsystem) {
        Object peers = subsystem.status().get("peers");
        if (!(peers instanceof java.util.Map<?, ?> peerMap) || peerMap.isEmpty())
            return false;
        return peerMap.values().stream().allMatch(Boolean.TRUE::equals);
    }

    private static boolean hasPeerMessage(AppChainSubsystem subsystem, String messageId) {
        ReceivedAppMessage message = find(subsystem, messageId);
        return message != null && message.source() == ReceivedAppMessage.Source.PEER;
    }

    private static ReceivedAppMessage find(AppChainSubsystem subsystem, String messageId) {
        return subsystem.recentMessages(0).stream()
                .filter(m -> m.messageIdHex().equals(messageId))
                .findFirst()
                .orElse(null);
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

    /** Minimal ChainState for a server that only exercises app-layer messaging. */
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
