package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.appchain.AppChainConfig;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR app-layer/008.2 §3: rotation liveness (killing the block-1 proposer no
 * longer halts the chain — the fatal S1 failure) and partial-round recovery
 * (a proposer that voted but never certified finishes the SAME block after a
 * restart via the persisted locked proposal — the pool is memory-only, so
 * ONLY re-gossip can complete the height).
 */
@Timeout(180)
class AppChainRotationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainRotationIntegrationTest.class);
    private static final long MAGIC = 42;

    private static final byte[] KEY_A = seed(61);
    private static final byte[] KEY_B = seed(62);
    private static final byte[] KEY_C = seed(63);

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final Map<String, NodeServer> serversByName = new ConcurrentHashMap<>();
    private final Map<String, AppChainSubsystem> nodes = new ConcurrentHashMap<>();
    private final AtomicBoolean feeding = new AtomicBoolean();

    @AfterEach
    void tearDown() {
        feeding.set(false);
        for (AppChainSubsystem subsystem : nodes.values()) {
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
    void rotation_survivesProposerCrash() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> members = Set.of(pubA, pubB, pubC);

        int portA = freePort();
        int portB = freePort();
        int portC = freePort();

        // Shared L1 clock: every node reads the same L1 tip, which advances one slot every 300ms
        AtomicLong slot = new AtomicLong();
        ChainBlockReader l1 = l1Clock(slot);

        // Full mesh, rotating mode, threshold 2-of-3
        startRotating("a", KEY_A, members, portA, List.of(peer(portB), peer(portC)),
                l1, tempDir.resolve("rot-a"));
        startRotating("b", KEY_B, members, portB, List.of(peer(portA), peer(portC)),
                l1, tempDir.resolve("rot-b"));
        startRotating("c", KEY_C, members, portC, List.of(peer(portA), peer(portB)),
                l1, tempDir.resolve("rot-c"));

        awaitTrue("all connected", () -> nodes.values().stream().allMatch(this::connected));

        feeding.set(true);
        Thread feeder = new Thread(() -> {
            while (feeding.get()) {
                slot.incrementAndGet();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "l1-slot-feeder");
        feeder.setDaemon(true);
        feeder.start();

        // Block 1 finalizes under rotation
        nodes.get("a").submit("t", "first".getBytes(StandardCharsets.UTF_8));
        awaitTrue("height 1 everywhere",
                () -> nodes.values().stream().allMatch(n -> n.tipHeight() >= 1));

        // Kill whoever proposed block 1 — under S1 this would halt the chain forever
        byte[] proposer1 = nodes.get("a").block(1).orElseThrow().proposer();
        String victim = keyOf(proposer1, pubA, pubB, pubC);
        log.info("Killing block-1 proposer: node {}", victim);
        nodes.remove(victim).stop();

        List<AppChainSubsystem> survivors = new ArrayList<>(nodes.values());
        assertThat(survivors).hasSize(2); // threshold 2-of-3 still reachable

        survivors.get(0).submit("t", "after-crash".getBytes(StandardCharsets.UTF_8));
        awaitTrue("height 2 on both survivors (rotation took over)",
                () -> survivors.stream().allMatch(n -> n.tipHeight() >= 2));

        assertThat(survivors.get(0).stateRoot()).isEqualTo(survivors.get(1).stateRoot());
        byte[] proposer2 = survivors.get(0).block(2).orElseThrow().proposer();
        assertThat(HexUtil.encodeHexString(proposer2)).isNotEqualTo(
                HexUtil.encodeHexString(proposer1));
    }

    @Test
    void partialRound_finishesSameBlockAfterRestart_viaLockedRegossip() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        Set<String> members = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();
        Path dirA = tempDir.resolve("pr-a");

        // Fixed mode (recovery is mode-independent), threshold 2-of-2, B down:
        // A proposes, self-votes 1/2 — a partial round with no cert
        AppChainSubsystem nodeA = startFixed("a", KEY_A, members, pubA, portA,
                List.of(peer(portB)), dirA);
        String messageId = nodeA.submit("t", "must-survive".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(4_000); // proposal + self-vote happen; cert can't form
        assertThat(nodeA.tipHeight()).isEqualTo(0);

        // Crash A. Its pool (holding the message) is memory-only and gone —
        // from here, ONLY the persisted locked proposal can finalize height 1.
        nodes.remove("a").stop();

        // Restart A with the same ledger; bring B up
        AppChainSubsystem restartedA = startFixed("a", KEY_A, members, pubA, portA,
                List.of(peer(portB)), dirA);
        AppChainSubsystem nodeB = startFixed("b", KEY_B, members, pubA, portB,
                List.of(peer(portA)), tempDir.resolve("pr-b"));

        awaitTrue("partial round completed on both after restart",
                () -> restartedA.tipHeight() >= 1 && nodeB.tipHeight() >= 1);
        assertThat(restartedA.messageHeight(HexUtil.decodeHexString(messageId))).isPresent();
        assertThat(nodeB.messageHeight(HexUtil.decodeHexString(messageId))).isPresent();
        assertThat(restartedA.stateRoot()).isEqualTo(nodeB.stateRoot());
    }

    // ------------------------------------------------------------------

    private AppChainSubsystem startRotating(String name, byte[] key, Set<String> members,
                                            int serverPort, List<AppChainConfig.AppPeer> peers,
                                            ChainBlockReader l1, Path ledgerDir) throws Exception {
        AppChainConfig config = AppChainConfig.builder("rot-chain")
                .signingKeyHex(HexUtil.encodeHexString(key))
                .stateCommitmentIdentity(AppChainIntegrationFixtures.MPF)
                .memberKeysHex(members)
                .peers(peers)
                .threshold(2)
                .blockIntervalMs(500)
                .pluginSettings(Map.of(
                        "sequencer.mode", "rotating",
                        "sequencer.window-slots", "5"))
                .build();
        return start(name, config, serverPort, l1, ledgerDir);
    }

    private AppChainSubsystem startFixed(String name, byte[] key, Set<String> members,
                                         String proposerHex, int serverPort,
                                         List<AppChainConfig.AppPeer> peers, Path ledgerDir)
            throws Exception {
        AppChainConfig config = AppChainConfig.builder("pr-chain")
                .signingKeyHex(HexUtil.encodeHexString(key))
                .stateCommitmentIdentity(AppChainIntegrationFixtures.MPF)
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(proposerHex)
                .threshold(2)
                .blockIntervalMs(500)
                .build();
        return start(name, config, serverPort, null, ledgerDir);
    }

    private AppChainSubsystem start(String name, AppChainConfig config, int serverPort,
                                    ChainBlockReader l1, Path ledgerDir) throws Exception {
        // Restart case: release the port before binding a fresh server
        NodeServer previous = serversByName.remove(name);
        if (previous != null) {
            try {
                previous.shutdown();
            } catch (Exception ignored) {
            }
            Thread.sleep(500);
        }
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                ledgerDir.toString(), null, log);
        if (l1 != null) {
            subsystem.wireL1Chain(l1, null);
        }
        nodes.put(name, subsystem);
        NodeServer server = new NodeServer(serverPort,
                N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC, false, 0, false),
                new MinimalChainState(),
                null, null,
                subsystem.serverAgentFactories());
        servers.add(server);
        serversByName.put(name, server);
        Thread thread = new Thread(server::start);
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(800);
        subsystem.start();
        return subsystem;
    }

    /** An L1 whose tip is block {@code slot} at that slot: the rotating sequencer's slot clock reads only the tip. */
    private static ChainBlockReader l1Clock(AtomicLong slot) {
        return new ChainBlockReader() {
            @Override
            public ChainTip getLocalTip() {
                long current = slot.get();
                if (current == 0) {
                    return null;
                }
                byte[] hash = new byte[32];
                Arrays.fill(hash, (byte) (current % 251));
                hash[0] = (byte) (current >> 8);
                return new ChainTip(current, hash, current);
            }

            @Override
            public byte[] getBlockByNumber(long blockNumber) {
                return null;
            }

            @Override
            public Era getBlockEra(long blockNumber) {
                return null;
            }
        };
    }

    private boolean connected(AppChainSubsystem subsystem) {
        Object peers = subsystem.status().get("peers");
        return peers instanceof Map<?, ?> peerMap && !peerMap.isEmpty()
                && peerMap.values().stream().allMatch(Boolean.TRUE::equals);
    }

    private static String keyOf(byte[] proposer, String pubA, String pubB, String pubC) {
        String hex = HexUtil.encodeHexString(proposer);
        if (hex.equalsIgnoreCase(pubA)) return "a";
        if (hex.equalsIgnoreCase(pubB)) return "b";
        return "c";
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(300);
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
