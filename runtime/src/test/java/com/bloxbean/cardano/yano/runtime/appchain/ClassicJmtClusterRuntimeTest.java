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
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Network-level finality and late-member catch-up qualification for classic JMT. */
@Timeout(120)
class ClassicJmtClusterRuntimeTest {
    private static final Logger LOG = LoggerFactory.getLogger(ClassicJmtClusterRuntimeTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "classic-jmt-cluster";
    private static final byte[] KEY_A = repeated(0x41, 32);
    private static final byte[] KEY_B = repeated(0x42, 32);
    private static final byte[] KEY_C = repeated(0x43, 32);
    private static final StateCommitmentIdentity IDENTITY = StateCommitmentIdentity.explicit(
            StateCommitmentProfiles.CLASSIC_JMT, repeated(0x25, 32));

    @TempDir
    Path directory;

    private final List<AppChainSubsystem> nodes = new ArrayList<>();
    private final List<NodeServer> servers = new ArrayList<>();

    @AfterEach
    void closeCluster() {
        for (AppChainSubsystem node : nodes) {
            try {
                node.stop();
            } catch (Exception ignored) {
                // Preserve the primary test failure.
            }
        }
        for (NodeServer server : servers) {
            try {
                server.shutdown();
            } catch (Exception ignored) {
                // Preserve the primary test failure.
            }
        }
    }

    @Test
    void thresholdFinalityAndLateCatchUpReproduceClassicJmtRoots() throws Exception {
        String proposer = publicKey(KEY_A);
        Set<String> members = Set.of(proposer, publicKey(KEY_B), publicKey(KEY_C));
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();

        AppChainSubsystem nodeA = start("a", KEY_A, members, proposer, portA,
                List.of(peer(portB), peer(portC)));
        AppChainSubsystem nodeB = start("b", KEY_B, members, proposer, portB,
                List.of(peer(portA), peer(portC)));
        await("initial members connected", () -> connected(nodeA) && connected(nodeB));

        for (int expectedHeight = 1; expectedHeight <= 3; expectedHeight++) {
            nodeA.submit("ordered", ("entry-" + expectedHeight)
                    .getBytes(StandardCharsets.UTF_8));
            int height = expectedHeight;
            await("two-member finality at height " + height,
                    () -> nodeA.tipHeight() >= height && nodeB.tipHeight() >= height);
            assertFinalizedRoot(nodeA, nodeB, height);
        }

        AppChainSubsystem lateNode = start("c", KEY_C, members, proposer, portC,
                List.of(peer(portA), peer(portB)));
        await("late member connected", () -> connected(lateNode));
        await("late member caught up", () -> lateNode.tipHeight() >= 3);

        assertThat(lateNode.stateRoot()).isEqualTo(nodeA.stateRoot());
        assertThat(lateNode.block(3).orElseThrow().cert().signatures()).hasSize(2);

        nodeA.submit("ordered", "after-catch-up".getBytes(StandardCharsets.UTF_8));
        await("all members finalized after catch-up", () -> nodeA.tipHeight() >= 4
                && nodeB.tipHeight() >= 4 && lateNode.tipHeight() >= 4);

        assertFinalizedRoot(nodeA, nodeB, 4);
        assertFinalizedRoot(nodeA, lateNode, 4);
        for (AppChainSubsystem node : List.of(nodeA, nodeB, lateNode)) {
            assertThat(node.stateCommitmentIdentity()).contains(IDENTITY);
            assertThat(node.stateIntegrity()).get().extracting(report -> report.valid())
                    .isEqualTo(true);
        }
    }

    private AppChainSubsystem start(
            String name,
            byte[] signingKey,
            Set<String> members,
            String proposer,
            int port,
            List<AppChainConfig.AppPeer> peers
    ) throws Exception {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(signingKey))
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(proposer)
                .threshold(2)
                .blockIntervalMs(300)
                .maxBlockMessages(1)
                .pluginSettings(IDENTITY.settings())
                .build();
        AppChainSubsystem node = new AppChainSubsystem(
                config, MAGIC, null, null, directory.resolve(name).toString(), null, LOG);
        nodes.add(node);
        NodeServer server = new NodeServer(port,
                N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC, false, 0, false),
                new EmptyChainState(), null, null, node.serverAgentFactories());
        servers.add(server);
        Thread serverThread = new Thread(server::start, "classic-jmt-server-" + name);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(500);
        node.start();
        return node;
    }

    private static void assertFinalizedRoot(
            AppChainSubsystem expected,
            AppChainSubsystem actual,
            long height
    ) {
        AppBlock expectedBlock = expected.block(height).orElseThrow();
        AppBlock actualBlock = actual.block(height).orElseThrow();
        assertThat(expectedBlock.cert().signatures()).hasSize(2);
        assertThat(actualBlock.cert().signatures()).hasSize(2);
        assertThat(actualBlock.stateRoot()).isEqualTo(expectedBlock.stateRoot());
        assertThat(actual.stateRoot()).isEqualTo(expected.stateRoot());
    }

    private static boolean connected(AppChainSubsystem node) {
        Object peers = node.status().get("peers");
        return peers instanceof java.util.Map<?, ?> peerMap
                && peerMap.values().stream().anyMatch(Boolean.TRUE::equals);
    }

    private static void await(String outcome, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for " + outcome);
    }

    private static AppChainConfig.AppPeer peer(int port) {
        return new AppChainConfig.AppPeer("localhost", port);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String publicKey(byte[] privateKey) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(privateKey));
    }

    private static byte[] repeated(int value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class EmptyChainState implements ChainState {
        @Override public void storeBlock(byte[] hash, Long number, Long slot, byte[] block) { }
        @Override public byte[] getBlock(byte[] hash) { return null; }
        @Override public boolean hasBlock(byte[] hash) { return false; }
        @Override public void storeBlockHeader(byte[] hash, Long number, Long slot, byte[] block) { }
        @Override public byte[] getBlockHeader(byte[] hash) { return null; }
        @Override public byte[] getBlockByNumber(Long number) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long number) { return null; }
        @Override public Point findNextBlock(Point point) { return null; }
        @Override public Point findNextBlockHeader(Point point) { return null; }
        @Override public List<Point> findBlocksInRange(Point from, Point to) {
            return Collections.emptyList();
        }
        @Override public Point findLastPointAfterNBlocks(Point from, long batchSize) { return null; }
        @Override public boolean hasPoint(Point point) { return false; }
        @Override public Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long number) { return null; }
        @Override public void rollbackTo(Long slot) { }
        @Override public ChainTip getTip() { return null; }
        @Override public ChainTip getHeaderTip() { return null; }
    }
}
