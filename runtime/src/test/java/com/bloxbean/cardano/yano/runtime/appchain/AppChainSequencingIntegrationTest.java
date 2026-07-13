package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
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
 * M2 exit-criteria test (ADR app-layer/005): a fixed sequencer and a member
 * finalize identical blocks with threshold certs; state roots match
 * byte-for-byte; the ledger survives a restart and continues.
 */
@Timeout(120)
class AppChainSequencingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainSequencingIntegrationTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "seq-chain";

    private static final byte[] KEY_A = seed(11); // proposer
    private static final byte[] KEY_B = seed(12); // member

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final List<AppChainSubsystem> subsystems = new ArrayList<>();

    @AfterEach
    void tearDown() {
        stopAll();
    }

    private void stopAll() {
        for (AppChainSubsystem subsystem : subsystems) {
            try {
                subsystem.stop();
            } catch (Exception ignored) {
            }
        }
        subsystems.clear();
        for (NodeServer server : servers) {
            try {
                server.shutdown();
            } catch (Exception ignored) {
            }
        }
        servers.clear();
    }

    @Test
    void sequencer_finalizesBlocks_identicalRoots_andSurvivesRestart() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        Set<String> members = Set.of(pubA, pubB);

        int portA = freePort();
        int portB = freePort();

        AppChainSubsystem nodeA = startNode("a", KEY_A, members, pubA, portA, List.of(peer(portB)));
        AppChainSubsystem nodeB = startNode("b", KEY_B, members, pubA, portB, List.of(peer(portA)));

        awaitTrue("peers connected", () -> connected(nodeA) && connected(nodeB));

        // Submit messages on both nodes; the proposer (A) sequences them
        String id1 = nodeA.submit("orders", "m1 from A".getBytes(StandardCharsets.UTF_8));
        String id2 = nodeB.submit("orders", "m2 from B".getBytes(StandardCharsets.UTF_8));

        awaitTrue("both nodes finalized the messages",
                () -> nodeA.messageHeight(HexUtil.decodeHexString(id1)).isPresent()
                        && nodeA.messageHeight(HexUtil.decodeHexString(id2)).isPresent()
                        && nodeB.messageHeight(HexUtil.decodeHexString(id1)).isPresent()
                        && nodeB.messageHeight(HexUtil.decodeHexString(id2)).isPresent());

        awaitTrue("tips equal", () -> nodeA.tipHeight() == nodeB.tipHeight() && nodeA.tipHeight() >= 1);

        long tip = nodeA.tipHeight();
        assertThat(nodeB.stateRoot()).isEqualTo(nodeA.stateRoot());
        assertThat(nodeA.stateRoot()).isNotEqualTo(new byte[32]);

        // Blocks byte-identical facts on both sides
        for (long h = 1; h <= tip; h++) {
            AppBlock blockOnA = nodeA.block(h).orElseThrow();
            AppBlock blockOnB = nodeB.block(h).orElseThrow();
            assertThat(blockOnB.stateRoot()).isEqualTo(blockOnA.stateRoot());
            assertThat(blockOnB.messagesRoot()).isEqualTo(blockOnA.messagesRoot());
            assertThat(blockOnA.cert().signatures().size()).isGreaterThanOrEqualTo(2);
            assertThat(blockOnB.cert().signatures().size()).isGreaterThanOrEqualTo(2);
        }

        // Inclusion proof exists for a finalized message id against the shared root
        assertThat(nodeA.stateProof(HexUtil.decodeHexString(id1))).isPresent();
        assertThat(nodeB.stateProof(HexUtil.decodeHexString(id1))).isPresent();

        byte[] rootBeforeRestart = nodeA.stateRoot();
        long tipBeforeRestart = nodeA.tipHeight();

        // ---- Restart both nodes on the same ledgers ----
        stopAll();

        AppChainSubsystem nodeA2 = startNode("a", KEY_A, members, pubA, portA, List.of(peer(portB)));
        AppChainSubsystem nodeB2 = startNode("b", KEY_B, members, pubA, portB, List.of(peer(portA)));

        assertThat(nodeA2.tipHeight()).isEqualTo(tipBeforeRestart);
        assertThat(nodeB2.tipHeight()).isEqualTo(tipBeforeRestart);
        assertThat(nodeA2.stateRoot()).isEqualTo(rootBeforeRestart);
        assertThat(nodeB2.stateRoot()).isEqualTo(rootBeforeRestart);

        awaitTrue("peers reconnected", () -> connected(nodeA2) && connected(nodeB2));

        // Chain continues after restart
        String id3 = nodeB2.submit("orders", "m3 after restart".getBytes(StandardCharsets.UTF_8));
        awaitTrue("post-restart message finalized on both",
                () -> nodeA2.messageHeight(HexUtil.decodeHexString(id3)).isPresent()
                        && nodeB2.messageHeight(HexUtil.decodeHexString(id3)).isPresent());
        awaitTrue("post-restart tips equal and advanced",
                () -> nodeA2.tipHeight() == nodeB2.tipHeight()
                        && nodeA2.tipHeight() > tipBeforeRestart);
        assertThat(nodeB2.stateRoot()).isEqualTo(nodeA2.stateRoot());

        // Already-finalized messages are never re-included (dedup vs ledger)
        long heightOfId1 = nodeA2.messageHeight(HexUtil.decodeHexString(id1)).orElseThrow();
        assertThat(heightOfId1).isLessThanOrEqualTo(tipBeforeRestart);

        log.info("A status: {}", nodeA2.status());
        log.info("B status: {}", nodeB2.status());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AppChainSubsystem startNode(String name, byte[] signingKey, Set<String> members,
                                        String proposerHex, int serverPort,
                                        List<AppChainConfig.AppPeer> peers) throws Exception {
        AppChainConfig config = new AppChainConfig(
                CHAIN_ID,
                HexUtil.encodeHexString(signingKey),
                members,
                peers,
                65536, 3600, 600,
                proposerHex,
                2,      // threshold: both members must co-sign
                500,    // fast proposer tick for tests
                100,
                AppChainConfig.DEFAULT_STATE_MACHINE,
                null,
                null, 0, java.util.List.of(),
                false, 0, java.util.Map.of());
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                tempDir.resolve("ledger-" + name).toString(), log);
        subsystems.add(subsystem);

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
