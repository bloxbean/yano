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
 * Fail-closed tests (ADR app-layer/005 D2): a member that is not the
 * configured proposer cannot get blocks finalized on honest nodes, no matter
 * how well-formed its proposals are.
 */
@Timeout(90)
class AppChainAdversarialTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainAdversarialTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "adv-chain";

    private static final byte[] KEY_A = seed(21); // legitimate proposer (not started in this test)
    private static final byte[] KEY_B = seed(22); // honest member
    private static final byte[] KEY_C = seed(23); // rogue member claiming to be proposer

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
    void rogueMemberProposals_neverFinalizeOnHonestNodes() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> members = Set.of(pubA, pubB, pubC);

        int portB = freePort();

        // Honest node B: configured proposer is A, threshold 2
        AppChainSubsystem nodeB = startNode("b", KEY_B, members, pubA, portB, List.of());

        // Rogue node C: misconfigured to believe IT is the proposer; targets B
        AppChainSubsystem nodeC = startNode("c", KEY_C, members, pubC, 0,
                List.of(new AppChainConfig.AppPeer("localhost", portB)));

        awaitTrue("C connected to B", () -> connected(nodeC));

        // C submits a message and (as self-proclaimed proposer) proposes blocks
        String idFromC = nodeC.submit("attack", "rogue payload".getBytes(StandardCharsets.UTF_8));

        // Transport status becomes connected when the session is ready, just
        // before the first application message is necessarily delivered. Wait
        // for that independent precondition instead of making the security
        // assertion depend on scheduler timing under a busy full-suite run.
        awaitTrue("rogue member message reached B", () -> nodeB.recentMessages(10).stream()
                .anyMatch(m -> m.messageIdHex().equals(idFromC)));

        // Give C several proposer ticks; B must never finalize anything
        Thread.sleep(8000);

        assertThat(nodeB.tipHeight())
                .as("honest node must not finalize blocks from a non-proposer")
                .isEqualTo(0);
        assertThat(nodeB.messageHeight(HexUtil.decodeHexString(idFromC))).isEmpty();
        // C itself can't finalize either: threshold 2, and B never votes for it
        assertThat(nodeC.tipHeight()).isEqualTo(0);

        // The rogue's *message* is still valid app traffic (C is a member) — only
        // its sequencing authority is rejected; the message sits in B's pool.
        assertThat(nodeB.recentMessages(10))
                .anyMatch(m -> m.messageIdHex().equals(idFromC));

        log.info("B status after attack: {}", nodeB.status());
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
                null, 0, java.util.List.of(),
                false, 0, java.util.Map.of());
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
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + what);
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
