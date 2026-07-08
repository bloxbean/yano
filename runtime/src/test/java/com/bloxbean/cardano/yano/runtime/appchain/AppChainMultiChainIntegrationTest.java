package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
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
 * ADR-006 E5.2: one node hosts multiple app chains behind shared inbound
 * agents (AppChainManager). Chains sequence independently, messages stay
 * isolated per chain, and both replicate across the node pair.
 */
@Timeout(120)
class AppChainMultiChainIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainMultiChainIntegrationTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_1 = "mc-chain-1";
    private static final String CHAIN_2 = "mc-chain-2";

    private static final byte[] KEY_A = seed(51);
    private static final byte[] KEY_B = seed(52);

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final List<AppChainManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainManager manager : managers) {
            try {
                manager.stop();
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
    void twoChains_oneNodePair_isolatedAndIndependentlySequenced() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        Set<String> members = Set.of(pubA, pubB);

        int portA = freePort();
        int portB = freePort();

        AppChainManager nodeA = startNode("a", KEY_A, members, pubA, portA, portB);
        AppChainManager nodeB = startNode("b", KEY_B, members, pubA, portB, portA);

        awaitTrue("all peers connected on both nodes",
                () -> allConnected(nodeA) && allConnected(nodeB));

        AppChainGateway chain1OnA = nodeA.byId(CHAIN_1).orElseThrow();
        AppChainGateway chain2OnA = nodeA.byId(CHAIN_2).orElseThrow();
        AppChainGateway chain1OnB = nodeB.byId(CHAIN_1).orElseThrow();
        AppChainGateway chain2OnB = nodeB.byId(CHAIN_2).orElseThrow();

        // Submit to different chains on different nodes
        String idOnChain1 = chain1OnA.submit("t", "for chain 1".getBytes(StandardCharsets.UTF_8));
        String idOnChain2 = chain2OnB.submit("t", "for chain 2".getBytes(StandardCharsets.UTF_8));

        awaitTrue("chain-1 message finalized on both nodes",
                () -> chain1OnA.messageHeight(HexUtil.decodeHexString(idOnChain1)).isPresent()
                        && chain1OnB.messageHeight(HexUtil.decodeHexString(idOnChain1)).isPresent());
        awaitTrue("chain-2 message finalized on both nodes",
                () -> chain2OnA.messageHeight(HexUtil.decodeHexString(idOnChain2)).isPresent()
                        && chain2OnB.messageHeight(HexUtil.decodeHexString(idOnChain2)).isPresent());

        // Isolation: neither chain sees the other's message or state
        assertThat(chain2OnA.messageHeight(HexUtil.decodeHexString(idOnChain1))).isEmpty();
        assertThat(chain1OnB.messageHeight(HexUtil.decodeHexString(idOnChain2))).isEmpty();
        assertThat(chain1OnA.stateRoot()).isNotEqualTo(chain2OnA.stateRoot());

        // Replicated consistency per chain
        awaitTrue("chain tips equal across nodes",
                () -> chain1OnA.tipHeight() == chain1OnB.tipHeight()
                        && chain2OnA.tipHeight() == chain2OnB.tipHeight());
        assertThat(chain1OnB.stateRoot()).isEqualTo(chain1OnA.stateRoot());
        assertThat(chain2OnB.stateRoot()).isEqualTo(chain2OnA.stateRoot());

        // Registry surface
        assertThat(nodeA.all()).hasSize(2);
        assertThat(nodeA.single()).isEmpty(); // ambiguous with two chains
    }

    /** Starts a node hosting BOTH chains behind one NodeServer (shared agents). */
    private AppChainManager startNode(String name, byte[] signingKey, Set<String> members,
                                      String proposerHex, int serverPort, int peerPort) throws Exception {
        AppChainSubsystem chain1 = subsystem(name, CHAIN_1, signingKey, members, proposerHex, peerPort);
        AppChainSubsystem chain2 = subsystem(name, CHAIN_2, signingKey, members, proposerHex, peerPort);
        AppChainManager manager = new AppChainManager(List.of(chain1, chain2), log);
        managers.add(manager);

        NodeServer server = new NodeServer(serverPort,
                N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC, false, 0, false),
                new MinimalChainState(),
                null, null,
                manager.serverAgentFactories());
        servers.add(server);
        Thread thread = new Thread(server::start);
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(800);

        manager.start();
        return manager;
    }

    private AppChainSubsystem subsystem(String name, String chainId, byte[] signingKey,
                                        Set<String> members, String proposerHex, int peerPort) {
        AppChainConfig config = new AppChainConfig(
                chainId,
                HexUtil.encodeHexString(signingKey),
                members,
                List.of(new AppChainConfig.AppPeer("localhost", peerPort)),
                65536, 3600, 600,
                proposerHex,
                2,
                500,
                100,
                AppChainConfig.DEFAULT_STATE_MACHINE,
                null,
                null, 0, java.util.List.of());
        return new AppChainSubsystem(config, MAGIC, null, null,
                tempDir.resolve("ledger-" + name).toString(), null, log);
    }

    private static boolean allConnected(AppChainManager manager) {
        for (AppChainGateway gateway : manager.all()) {
            Object peers = gateway.status().get("peers");
            if (!(peers instanceof Map<?, ?> peerMap) || peerMap.isEmpty()
                    || !peerMap.values().stream().allMatch(Boolean.TRUE::equals)) {
                return false;
            }
        }
        return true;
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
