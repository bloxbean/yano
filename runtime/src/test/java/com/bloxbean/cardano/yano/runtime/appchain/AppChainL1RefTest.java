package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR app-layer/008.1 I1.3: followers verify a proposal's L1 reference against
 * their OWN L1 view — matching views finalize, a fabricated ref is rejected
 * fail-closed, a briefly-lagging follower defers and then votes, and a chain
 * configured for L1 refs without an L1 feed refuses to start.
 */
@Timeout(120)
class AppChainL1RefTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainL1RefTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "l1ref-chain";
    private static final int DEPTH = 2;

    private static final byte[] KEY_A = seed(51); // proposer
    private static final byte[] KEY_B = seed(52); // follower

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final List<AppChainSubsystem> subsystems = new ArrayList<>();

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
    void matchingL1Views_blockCarriesVerifiedRef() throws Exception {
        EventBus busA = new SimpleEventBus();
        EventBus busB = new SimpleEventBus();
        AppChainSubsystem[] nodes = startPair(busA, busB);

        // Identical L1 views on both nodes: slots 1..10
        feedL1(busA, 1, 10, 0);
        feedL1(busB, 1, 10, 0);

        nodes[0].submit("t", "hello".getBytes(StandardCharsets.UTF_8));
        awaitTrue("block finalized on both",
                () -> nodes[0].tipHeight() >= 1 && nodes[1].tipHeight() >= 1);

        long l1Slot = nodes[1].block(1).orElseThrow().l1Slot();
        assertThat(l1Slot).isGreaterThan(0);
        assertThat(l1Slot).isLessThanOrEqualTo(10 - DEPTH); // stable-depth rule
    }

    @Test
    void fabricatedL1Ref_rejectedByFollower() throws Exception {
        EventBus busA = new SimpleEventBus();
        EventBus busB = new SimpleEventBus();
        AppChainSubsystem[] nodes = startPair(busA, busB);

        // Same slots, DIFFERENT hashes: the proposer's refs don't exist on B's chain
        feedL1(busA, 1, 10, 0);
        feedL1(busB, 1, 10, 100);

        nodes[0].submit("t", "poison".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(6_000);
        assertThat(nodes[0].tipHeight()).isEqualTo(0);
        assertThat(nodes[1].tipHeight()).isEqualTo(0);
    }

    @Test
    void laggingFollower_defersThenVotes() throws Exception {
        EventBus busA = new SimpleEventBus();
        EventBus busB = new SimpleEventBus();
        AppChainSubsystem[] nodes = startPair(busA, busB);

        // A is ahead (1..10 → stable ref slot 8); B has only 1..6 → ref is AHEAD for B
        feedL1(busA, 1, 10, 0);
        feedL1(busB, 1, 6, 0);

        nodes[0].submit("t", "patience".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1_500); // proposal arrives at B and gets deferred

        feedL1(busB, 7, 10, 0); // B's L1 catches up within the deferral window
        awaitTrue("deferred proposal finalized on both",
                () -> nodes[0].tipHeight() >= 1 && nodes[1].tipHeight() >= 1);

        Object deferrals = nodes[1].status().get("l1RefDeferrals");
        assertThat((Long) deferrals).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void l1RefsConfiguredWithoutEventBus_failsFast() {
        AppChainConfig config = builder("ff", pubHex(KEY_A), List.of()).build();
        AppChainSubsystem node = new AppChainSubsystem(config, MAGIC, null, null,
                tempDir.resolve("ledger-ff").toString(), null, log);
        subsystems.add(node);
        assertThatThrownBy(node::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no L1 event feed");
    }

    // ------------------------------------------------------------------

    /** Publish synthetic applied L1 blocks; hash = f(slot + hashOffset). */
    private static void feedL1(EventBus bus, long fromSlot, long toSlot, int hashOffset) {
        for (long slot = fromSlot; slot <= toSlot; slot++) {
            byte[] hash = new byte[32];
            Arrays.fill(hash, (byte) (slot + hashOffset));
            bus.publish(new BlockAppliedEvent(null, slot, slot, HexUtil.encodeHexString(hash), null),
                    EventMetadata.builder().build(), PublishOptions.builder().build());
        }
    }

    private AppChainSubsystem[] startPair(EventBus busA, EventBus busB) throws Exception {
        int portA = freePort();
        int portB = freePort();
        AppChainSubsystem nodeA = startNode("a", KEY_A, busA, portA, List.of(peer(portB)));
        AppChainSubsystem nodeB = startNode("b", KEY_B, busB, portB, List.of(peer(portA)));
        awaitTrue("A/B connected", () -> connected(nodeA) && connected(nodeB));
        return new AppChainSubsystem[]{nodeA, nodeB};
    }

    private AppChainConfig.Builder builder(String name, String proposerHex,
                                           List<AppChainConfig.AppPeer> peers) {
        return AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(name.equals("a") || name.equals("ff") ? KEY_A : KEY_B))
                .memberKeysHex(Set.of(pubHex(KEY_A), pubHex(KEY_B)))
                .peers(peers)
                .proposerKeyHex(proposerHex)
                .threshold(2)
                .blockIntervalMs(500)
                .l1StabilityDepth(DEPTH);
    }

    private AppChainSubsystem startNode(String name, byte[] signingKey, EventBus eventBus,
                                        int serverPort, List<AppChainConfig.AppPeer> peers)
            throws Exception {
        AppChainConfig config = builder(name, pubHex(KEY_A), peers)
                .signingKeyHex(HexUtil.encodeHexString(signingKey))
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, eventBus, null,
                tempDir.resolve("ledger-" + name).toString(), null, log);
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
        long deadline = System.currentTimeMillis() + 45_000;
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
