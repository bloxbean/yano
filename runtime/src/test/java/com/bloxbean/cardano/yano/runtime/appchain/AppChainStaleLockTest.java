package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stale vote-lock recovery (I4.2, found by the Iteration-3 devnet gate): a
 * member whose locked proposal EXPIRED across a crash-restart previously
 * wedged its height forever. Now: the proposer proposes a FRESH block around
 * its spent vote without self-voting (threshold from OTHER members certifies
 * — the at-most-one-vote guarantee stands), and for threshold-unreachable
 * configs the operator unlock endpoint is the documented escape hatch.
 */
@Timeout(180)
class AppChainStaleLockTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainStaleLockTest.class);
    private static final long MAGIC = 42;

    private static final byte[] KEY_A = seed(71);
    private static final byte[] KEY_B = seed(72);
    private static final byte[] KEY_C = seed(73);

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final Map<String, NodeServer> serversByName = new ConcurrentHashMap<>();
    private final Map<String, AppChainSubsystem> nodes = new ConcurrentHashMap<>();

    @AfterEach
    void tearDown() {
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
    void staleLock_proposerMovesOnWithoutSelfVote_othersCertify() throws Exception {
        String pubA = pubHex(KEY_A);
        Set<String> members = Set.of(pubA, pubHex(KEY_B), pubHex(KEY_C));
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        Path dirA = tempDir.resolve("sl-a");

        // A proposes alone (2-of-3, B/C down): partial round, vote spent
        AppChainSubsystem nodeA = start("a", KEY_A, members, pubA, portA,
                List.of(peer(portB), peer(portC)), dirA);
        nodeA.submit("t", "wedged".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(3_000);
        assertThat(nodeA.tipHeight()).isEqualTo(0);

        // Crash A and make its persisted locked proposal EXPIRED (the gate's
        // crash-restart-past-TTL scenario, compressed in time)
        nodes.remove("a").stop();
        expireStoredLock(dirA, 1);

        // Restart A; bring B and C up. A must propose a FRESH block at height
        // 1 WITHOUT voting for it; B + C supply the 2-of-3 certificate.
        AppChainSubsystem restartedA = start("a", KEY_A, members, pubA, portA,
                List.of(peer(portB), peer(portC)), dirA);
        AppChainSubsystem nodeB = start("b", KEY_B, members, pubA, portB,
                List.of(peer(portA), peer(portC)), tempDir.resolve("sl-b"));
        AppChainSubsystem nodeC = start("c", KEY_C, members, pubA, portC,
                List.of(peer(portA), peer(portB)), tempDir.resolve("sl-c"));

        // The wedge is visible while unresolved
        awaitTrue("stale lock surfaced in status", () -> {
            Object sequencer = restartedA.status().get("sequencer");
            return sequencer instanceof Map<?, ?> map && map.containsKey("staleLockedHeight");
        });

        // The fresh proposal needs content (A's pool died with the crash)
        restartedA.submit("t", "fresh-after-wedge".getBytes(StandardCharsets.UTF_8));

        awaitTrue("height 1 finalized everywhere around the stale lock",
                () -> restartedA.tipHeight() >= 1 && nodeB.tipHeight() >= 1 && nodeC.tipHeight() >= 1);

        // The certificate must NOT carry A's signature — A never voted twice
        FinalityCert cert = restartedA.block(1).orElseThrow().cert();
        List<String> certSigners = cert.signatures().stream()
                .map(s -> HexUtil.encodeHexString(s.signer()).toLowerCase())
                .toList();
        assertThat(certSigners).doesNotContain(pubA.toLowerCase());
        assertThat(certSigners).hasSize(2);
        assertThat(restartedA.stateRoot()).isEqualTo(nodeB.stateRoot());
        assertThat(nodeB.stateRoot()).isEqualTo(nodeC.stateRoot());
    }

    @Test
    void thresholdUnreachable_operatorUnlockRestoresLiveness() throws Exception {
        String pubA = pubHex(KEY_A);
        Set<String> members = Set.of(pubA, pubHex(KEY_B));
        int portA = freePort();
        int portB = freePort();
        Path dirA = tempDir.resolve("ul-a");

        // 2-of-2: A's spent vote makes the threshold unreachable without it
        AppChainSubsystem nodeA = start("a", KEY_A, members, pubA, portA,
                List.of(peer(portB)), dirA);
        nodeA.submit("t", "wedged".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(3_000);

        // Unlock is REFUSED while the locked round is still recoverable
        assertThat(nodeA.unlockStaleRound()).isFalse();

        nodes.remove("a").stop();
        expireStoredLock(dirA, 1);
        AppChainSubsystem restartedA = start("a", KEY_A, members, pubA, portA,
                List.of(peer(portB)), dirA);
        AppChainSubsystem nodeB = start("b", KEY_B, members, pubA, portB,
                List.of(peer(portA)), tempDir.resolve("ul-b"));

        // Operator confirms no conflicting cert exists, then unlocks
        awaitTrue("unlock succeeds on the stale lock", () -> {
            try {
                return restartedA.unlockStaleRound();
            } catch (Exception e) {
                return false;
            }
        });

        restartedA.submit("t", "post-unlock".getBytes(StandardCharsets.UTF_8));
        awaitTrue("height 1 finalized after operator unlock",
                () -> restartedA.tipHeight() >= 1 && nodeB.tipHeight() >= 1);

        // A voted once more (consciously, post-unlock): 2-of-2 cert includes it
        FinalityCert cert = restartedA.block(1).orElseThrow().cert();
        assertThat(cert.signatures()).hasSize(2);
        assertThat(restartedA.stateRoot()).isEqualTo(nodeB.stateRoot());
    }

    // ------------------------------------------------------------------

    /** Rewrite the persisted locked-proposal envelope with an expired copy. */
    private void expireStoredLock(Path ledgerDir, long height) {
        // The subsystem opens its ledger at <base>/<chainId>
        AppLedgerStore store = new AppLedgerStore(ledgerDir.resolve("sl-chain").toString(), log);
        try {
            byte[] stored = store.voteLockEnvelope(height).orElseThrow(
                    () -> new AssertionError("No locked proposal stored at height " + height));
            AppMessage original = ConsensusCodec.decodeEnvelope(stored);
            AppMessage expired = AppMessage.builder()
                    .messageId(original.getMessageId())
                    .chainId(original.getChainId())
                    .topic(original.getTopic())
                    .sender(original.getSender())
                    .senderSeq(original.getSenderSeq())
                    .expiresAt(System.currentTimeMillis() / 1000 - 10)
                    .body(original.getBody())
                    .authScheme(original.getAuthScheme())
                    .authProof(original.getAuthProof())
                    .build();
            store.putVoteLockEnvelope(height, ConsensusCodec.encodeEnvelope(expired));
        } finally {
            store.close();
        }
    }

    private AppChainSubsystem start(String name, byte[] key, Set<String> members,
                                    String proposerHex, int serverPort,
                                    List<AppChainConfig.AppPeer> peers, Path ledgerDir)
            throws Exception {
        NodeServer previous = serversByName.remove(name);
        if (previous != null) {
            try {
                previous.shutdown();
            } catch (Exception ignored) {
            }
            Thread.sleep(500);
        }
        AppChainConfig config = AppChainConfig.builder("sl-chain")
                .signingKeyHex(HexUtil.encodeHexString(key))
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(proposerHex)
                .threshold(2)
                .blockIntervalMs(500)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                ledgerDir.toString(), null, log);
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

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }

    private static String pubHex(byte[] privateKey) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(privateKey));
    }

    private static class MinimalChainState implements com.bloxbean.cardano.yaci.core.storage.ChainState {
        @Override public void storeBlock(byte[] blockHash, Long blockNumber, Long slot, byte[] block) {}
        @Override public byte[] getBlock(byte[] blockHash) { return null; }
        @Override public boolean hasBlock(byte[] blockHash) { return false; }
        @Override public void storeBlockHeader(byte[] blockHash, Long blockNumber, Long slot, byte[] blockHeader) {}
        @Override public byte[] getBlockHeader(byte[] blockHash) { return null; }
        @Override public byte[] getBlockByNumber(Long blockNumber) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long blockNumber) { return null; }
        @Override public com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point findNextBlock(
                com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point currentPoint) { return null; }
        @Override public com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point findNextBlockHeader(
                com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point currentPoint) { return null; }
        @Override public List<com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point> findBlocksInRange(
                com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point from,
                com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point to) { return List.of(); }
        @Override public com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point findLastPointAfterNBlocks(
                com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point from, long batchSize) { return null; }
        @Override public boolean hasPoint(
                com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point point) { return false; }
        @Override public com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long blockNumber) { return null; }
        @Override public void rollbackTo(Long slot) {}
        @Override public com.bloxbean.cardano.yaci.core.storage.ChainTip getTip() { return null; }
        @Override public com.bloxbean.cardano.yaci.core.storage.ChainTip getHeaderTip() { return null; }
    }
}
