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
 * M4 exit-criteria test (ADR app-layer/005 D8): a member that joins after
 * blocks were finalized catches up over protocol 103 — fully verifying the
 * hash chain, finality certs and re-executed state roots — and then stays
 * current with new blocks.
 */
@Timeout(120)
class AppChainCatchUpIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainCatchUpIntegrationTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "catchup-chain";

    private static final byte[] KEY_A = seed(31); // proposer
    private static final byte[] KEY_B = seed(32); // member from the start
    private static final byte[] KEY_C = seed(33); // late joiner

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
    void lateJoiner_catchesUpAndStaysCurrent() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> members = Set.of(pubA, pubB, pubC);

        int portA = freePort();
        int portB = freePort();

        AppChainSubsystem nodeA = startNode("a", KEY_A, members, pubA, portA, List.of(peer(portB)));
        AppChainSubsystem nodeB = startNode("b", KEY_B, members, pubA, portB, List.of(peer(portA)));

        awaitTrue("A/B connected", () -> connected(nodeA) && connected(nodeB));

        // Finalize a few blocks before C exists
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(nodeA.submit("history", ("msg-" + i).getBytes(StandardCharsets.UTF_8)));
            int expected = i;
            awaitTrue("block " + expected + " finalized on A and B",
                    () -> nodeA.tipHeight() >= expected && nodeB.tipHeight() >= expected);
        }
        long tipBeforeJoin = nodeA.tipHeight();
        assertThat(tipBeforeJoin).isGreaterThanOrEqualTo(3);

        // C joins late with an empty ledger, pointing at A
        AppChainSubsystem nodeC = startNode("c", KEY_C, members, pubA, 0, List.of(peer(portA)));
        assertThat(nodeC.tipHeight()).isEqualTo(0);

        awaitTrue("C caught up to pre-join tip",
                () -> nodeC.tipHeight() >= tipBeforeJoin);
        assertThat(nodeC.stateRoot()).isEqualTo(nodeA.stateRoot());
        for (String id : ids) {
            assertThat(nodeC.messageHeight(HexUtil.decodeHexString(id))).isPresent();
        }

        // New blocks after the join reach C too (catch-up keeps polling)
        String idAfter = nodeB.submit("history", "after-join".getBytes(StandardCharsets.UTF_8));
        awaitTrue("post-join message finalized on all three",
                () -> nodeA.messageHeight(HexUtil.decodeHexString(idAfter)).isPresent()
                        && nodeC.messageHeight(HexUtil.decodeHexString(idAfter)).isPresent());
        awaitTrue("all tips equal", () -> nodeA.tipHeight() == nodeC.tipHeight()
                && nodeB.tipHeight() == nodeC.tipHeight());
        assertThat(nodeC.stateRoot()).isEqualTo(nodeA.stateRoot());

        log.info("C status after catch-up: {}", nodeC.status());
    }

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
                2,
                500,
                100,
                AppChainConfig.DEFAULT_STATE_MACHINE,
                null,
                null, 0);
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                tempDir.resolve("ledger-" + name).toString(), log);
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
