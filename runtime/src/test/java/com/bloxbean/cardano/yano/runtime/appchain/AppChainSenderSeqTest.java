package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
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
 * ADR app-layer/008.1 I1.2: sender-seq light enforcement.
 * <ul>
 *   <li>Admission (always on): a seq at or below the sender's finalized floor
 *       is a replay — dropped with reason {@code stale_seq}.</li>
 *   <li>Consensus (behind {@code message.enforce-sender-seq}): an enforcing
 *       follower rejects a block whose per-sender seqs are not strictly
 *       increasing above the floor — the same-block duplicate path a dishonest
 *       proposer could otherwise use.</li>
 *   <li>Restarts never reuse a lower own-seq (wall-clock seed + ledger floor).</li>
 * </ul>
 */
@Timeout(120)
class AppChainSenderSeqTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainSenderSeqTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "seq-chain";

    private static final byte[] KEY_A = seed(41); // proposer
    private static final byte[] KEY_B = seed(42); // follower

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
    void staleSeqReplay_droppedAtAdmission() throws Exception {
        String pubA = pubHex(KEY_A);
        AppChainSubsystem node = startSingle("adm", KEY_A, Set.of(pubA), pubA, 300, false);

        node.submit("t", "first".getBytes(StandardCharsets.UTF_8));
        awaitTrue("first finalized", () -> node.tipHeight() >= 1);
        long finalizedSeq = node.recentMessages(1).get(0).senderSeq();

        // Same sender, same (already finalized) seq, different body → replay
        node.onInboundMessages(List.of(
                signedMessage(KEY_A, "t", finalizedSeq, "replayed-different-body")));

        Map<String, Object> status = node.status();
        @SuppressWarnings("unchecked")
        Map<String, Long> drops = (Map<String, Long>) status.get("drops");
        assertThat(drops.get("stale_seq")).isEqualTo(1L);
    }

    @Test
    void restart_ownSeqStaysAboveFinalizedFloor() throws Exception {
        String pubA = pubHex(KEY_A);
        String dir = tempDir.resolve("restart").toString();

        AppChainSubsystem first = new AppChainSubsystem(config("r", KEY_A, Set.of(pubA), pubA,
                List.of(), 300, false), MAGIC, null, null, dir, null, log);
        subsystems.add(first);
        first.start();
        first.submit("t", "before".getBytes(StandardCharsets.UTF_8));
        awaitTrue("pre-restart finalized", () -> first.tipHeight() >= 1);
        long seqBefore = first.recentMessages(1).get(0).senderSeq();
        first.stop();

        AppChainSubsystem second = new AppChainSubsystem(config("r", KEY_A, Set.of(pubA), pubA,
                List.of(), 300, false), MAGIC, null, null, dir, null, log);
        subsystems.add(second);
        second.start();
        second.submit("t", "after".getBytes(StandardCharsets.UTF_8));
        awaitTrue("post-restart finalized", () -> second.tipHeight() >= 2);
        long seqAfter = second.recentMessages(1).get(0).senderSeq();

        assertThat(seqAfter).isGreaterThan(seqBefore);
    }

    @Test
    void enforceOn_honestPathUnaffected() throws Exception {
        String pubA = pubHex(KEY_A);
        AppChainSubsystem node = startSingle("honest", KEY_A, Set.of(pubA), pubA, 300, true);

        for (int i = 1; i <= 3; i++) {
            node.submit("t", ("m-" + i).getBytes(StandardCharsets.UTF_8));
            int expected = i;
            awaitTrue("block " + expected, () -> node.tipHeight() >= expected);
        }
        assertThat(node.tipHeight()).isEqualTo(3);
    }

    @Test
    void duplicateSeqInOneBlock_rejectedByEnforcingFollower() throws Exception {
        AppChainSubsystem[] nodes = startPair(true);
        AppChainSubsystem nodeA = nodes[0]; // proposer, enforcement OFF (dishonest model)
        AppChainSubsystem nodeB = nodes[1]; // follower, enforcement ON

        injectDuplicateSeqPair(nodeA);
        // First proposer tick is one interval away — both messages land in ONE
        // block; the enforcing follower must refuse to vote, so with threshold 2
        // nothing ever finalizes.
        Thread.sleep(9_000);
        assertThat(nodeA.tipHeight()).isEqualTo(0);
        assertThat(nodeB.tipHeight()).isEqualTo(0);
    }

    @Test
    void duplicateSeqInOneBlock_acceptedWhenEnforcementOff() throws Exception {
        AppChainSubsystem[] nodes = startPair(false);
        AppChainSubsystem nodeA = nodes[0];
        AppChainSubsystem nodeB = nodes[1];

        injectDuplicateSeqPair(nodeA);
        // Control: same attack, follower NOT enforcing → the duplicate-seq
        // block finalizes (documents the default-off behavior this release)
        awaitTrue("duplicate-seq block finalized on both",
                () -> nodeA.tipHeight() >= 1 && nodeB.tipHeight() >= 1);
    }

    // ------------------------------------------------------------------

    /** Two crafted valid member messages with the SAME sender-seq. */
    private void injectDuplicateSeqPair(AppChainSubsystem nodeA) {
        long seq = 1_000_000;
        nodeA.onInboundMessages(List.of(
                signedMessage(KEY_A, "t", seq, "payload-x"),
                signedMessage(KEY_A, "t", seq, "payload-y")));
    }

    private AppChainSubsystem[] startPair(boolean followerEnforces) throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        Set<String> members = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();

        // Long block interval: both crafted messages are pooled before the
        // first tick, guaranteeing the same-block scenario
        AppChainSubsystem nodeA = startNode("a", KEY_A, members, pubA, portA,
                List.of(peer(portB)), 3_000, 2, false);
        AppChainSubsystem nodeB = startNode("b", KEY_B, members, pubA, portB,
                List.of(peer(portA)), 3_000, 2, followerEnforces);
        awaitTrue("A/B connected", () -> connected(nodeA) && connected(nodeB));
        return new AppChainSubsystem[]{nodeA, nodeB};
    }

    private AppChainSubsystem startSingle(String name, byte[] key, Set<String> members,
                                          String proposerHex, long intervalMs,
                                          boolean enforce) throws Exception {
        return startNode(name, key, members, proposerHex, 0, List.of(), intervalMs, 1, enforce);
    }

    private AppChainSubsystem startNode(String name, byte[] signingKey, Set<String> members,
                                        String proposerHex, int serverPort,
                                        List<AppChainConfig.AppPeer> peers, long intervalMs,
                                        int threshold, boolean enforce) throws Exception {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(signingKey))
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(proposerHex)
                .threshold(threshold)
                .blockIntervalMs(intervalMs)
                .maxBlockMessages(100)
                .enforceSenderSeq(enforce)
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
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

    private AppChainConfig config(String name, byte[] signingKey, Set<String> members,
                                  String proposerHex, List<AppChainConfig.AppPeer> peers,
                                  long intervalMs, boolean enforce) {
        return AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(signingKey))
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(proposerHex)
                .threshold(1)
                .blockIntervalMs(intervalMs)
                .enforceSenderSeq(enforce)
                .build();
    }

    /** A properly signed member envelope with a chosen sender-seq. */
    private static AppMessage signedMessage(byte[] seedKey, String topic, long seq, String body) {
        AppMessageSigner signer = new AppMessageSigner(HexUtil.encodeHexString(seedKey));
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        long expiresAt = System.currentTimeMillis() / 1000 + 600;
        byte[] signedBody = AppMessage.signedBodyBytes(CHAIN_ID, topic,
                signer.publicKey(), seq, expiresAt, payload);
        byte[] signature = signer.sign(signedBody);
        byte[] messageId = AppMessage.computeMessageId(CHAIN_ID, topic,
                signer.publicKey(), seq, expiresAt, payload);
        return AppMessage.builder()
                .messageId(messageId)
                .chainId(CHAIN_ID)
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(seq)
                .expiresAt(expiresAt)
                .body(payload)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signature)
                .build();
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
