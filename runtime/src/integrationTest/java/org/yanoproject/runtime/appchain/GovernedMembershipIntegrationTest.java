package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.MembershipChangeRejectedException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
 * ADR app-layer/008.3 §3: chain-governed membership — a change activates only
 * when threshold-many members submit the identical command; guard rails void
 * invalid activations deterministically; a late-joining NEW member derives the
 * full membership history purely from replay; half-approved commands expire.
 */
@Timeout(240)
class GovernedMembershipIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GovernedMembershipIntegrationTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN_ID = "gov-chain";

    private static final byte[] KEY_A = seed(71); // fixed proposer
    private static final byte[] KEY_B = seed(72);
    private static final byte[] KEY_C = seed(73); // added via governance
    private static final byte[] KEY_D = seed(74); // fourth member, never started

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final List<AppChainSubsystem> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainSubsystem node : nodes) {
            try {
                node.stop();
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
    void thresholdApproval_activates_andLateJoinerDerivesHistory() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> genesis = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();

        AppChainSubsystem nodeA = start("a", KEY_A, genesis, portA, List.of(peer(portB)), 600);
        AppChainSubsystem nodeB = start("b", KEY_B, genesis, portB, List.of(peer(portA)), 600);
        awaitTrue("A/B connected", () -> connected(nodeA) && connected(nodeB));

        // ONE member's command changes nothing
        nodeA.addMember(pubC);
        awaitTrue("first command finalized", () -> nodeA.tipHeight() >= 1 && nodeB.tipHeight() >= 1);
        assertThat(nodeA.members()).containsExactlyInAnyOrder(pubA, pubB);
        assertThat(nodeB.members()).containsExactlyInAnyOrder(pubA, pubB);

        // The SECOND identical command reaches the threshold → activates on BOTH
        nodeB.addMember(pubC);
        awaitTrue("add-C activated on both",
                () -> nodeA.members().contains(pubC) && nodeB.members().contains(pubC));
        assertThat(nodeA.members()).hasSize(3);
        assertThat(nodeB.effectiveThreshold()).isEqualTo(2);
        assertThat(nodeA.status())
                .containsEntry("membershipMode", "governed")
                .containsEntry("membershipActiveMembers", 2)
                .containsEntry("membershipActiveThreshold", 2)
                .containsEntry("memberActiveForNextBlock", true)
                .containsKeys("membershipEpochFromHeight", "membershipEpochActive");
        assertThat((long) nodeA.status().get("membershipEpochFromHeight"))
                .isGreaterThan(nodeA.tipHeight());

        // Ordinary traffic still flows after the governance change
        String id = nodeA.submit("t", "post-governance".getBytes(StandardCharsets.UTF_8));
        awaitTrue("post-governance message finalized",
                () -> nodeB.messageHeight(HexUtil.decodeHexString(id)).isPresent());

        // LATE JOINER: C starts fresh with the ORIGINAL genesis list (it is
        // not in it!) and must derive its own membership purely from replay
        AppChainSubsystem nodeC = start("c", KEY_C, genesis, 0, List.of(peer(portA)), 600);
        awaitTrue("C caught up and derived the epochs",
                () -> nodeC.tipHeight() >= nodeA.tipHeight()
                        && nodeC.members().contains(pubC));
        assertThat(nodeC.stateRoot()).isEqualTo(nodeA.stateRoot());
        assertThat(nodeC.members()).containsExactlyInAnyOrderElementsOf(nodeA.members());
    }

    @Test
    void guardRail_invalidThreshold_isVoidDeterministically() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        Set<String> genesis = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();

        AppChainSubsystem nodeA = start("ga", KEY_A, genesis, portA, List.of(peer(portB)), 600);
        AppChainSubsystem nodeB = start("gb", KEY_B, genesis, portB, List.of(peer(portA)), 600);
        awaitTrue("connected", () -> connected(nodeA) && connected(nodeB));

        // threshold 5 > member count: the admin path refuses it up front ...
        assertQuorumRejected(() -> nodeA.setThreshold(5));
        // ... and a command that still reaches the chain activates VOID on every node
        byte[] command = GovernedMembership.encodeCommand(GovernedMembership.OP_SET_THRESHOLD,
                null, 5, GovernedMembership.DEFAULT_ACTIVATION_LAG);
        nodeA.submitGovernance(command);
        nodeB.submitGovernance(command);
        awaitTrue("both commands finalized", () -> nodeA.tipHeight() >= 1 && nodeB.tipHeight() >= 1);

        String id = nodeA.submit("t", "still-alive".getBytes(StandardCharsets.UTF_8));
        awaitTrue("chain alive after void activation",
                () -> nodeB.messageHeight(HexUtil.decodeHexString(id)).isPresent());
        assertThat(nodeA.effectiveThreshold()).isEqualTo(2);
        assertThat(nodeB.effectiveThreshold()).isEqualTo(2);
    }

    /**
     * bloxbean/yano#163: 2-of-3 plus one member would be 2-of-4, whose quorums
     * need not intersect in an honest member ({@code 2t - n > f} fails for
     * f = 0). Before the fix, the epoch activated and every proposal from its
     * first height threw, stalling the chain. The admin path now refuses the
     * add; a command that still reaches the chain is void on every member and
     * the chain stays live past the height where 2-of-4 would have started.
     */
    @Test
    void guardRail_addBreakingQuorumIntersection_isVoid_andChainStaysLive() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        String pubD = pubHex(KEY_D);
        Set<String> genesis = Set.of(pubA, pubB, pubC);
        List<AppChainSubsystem> cluster = startThreeNodeCluster("q", genesis);
        AppChainSubsystem nodeA = cluster.get(0);
        AppChainSubsystem nodeB = cluster.get(1);
        AppChainSubsystem nodeC = cluster.get(2);

        assertQuorumRejected(() -> nodeA.addMember(pubD));
        byte[] add = GovernedMembership.encodeCommand(GovernedMembership.OP_ADD,
                HexUtil.decodeHexString(pubD), 0, GovernedMembership.DEFAULT_ACTIVATION_LAG);
        nodeA.submitGovernance(add);
        nodeB.submitGovernance(add);
        long approvalHeight = awaitFinalizedTraffic(nodeA, cluster, "after-add");
        // Past every height where a 2-of-4 epoch could have started
        long target = approvalHeight + GovernedMembership.DEFAULT_ACTIVATION_LAG + 2;
        for (int i = 0; nodeC.tipHeight() < target; i++) {
            awaitFinalizedTraffic(nodeA, cluster, "past-activation-" + i);
        }

        for (AppChainSubsystem node : cluster) {
            assertThat(node.members()).containsExactlyInAnyOrder(pubA, pubB, pubC);
            assertThat(node.effectiveThreshold()).isEqualTo(2);
            assertThat(node.stateRoot()).isEqualTo(nodeA.stateRoot());
        }
    }

    /**
     * bloxbean/yano#163: the supported way to grow 2-of-3 to four members —
     * govern threshold 3 first (3-of-3), let it take effect, then add the
     * fourth member (3-of-4 satisfies the quorum rules).
     */
    @Test
    void growThreeToFour_raiseThresholdFirst_thenAdd() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        String pubD = pubHex(KEY_D);
        Set<String> genesis = Set.of(pubA, pubB, pubC);
        List<AppChainSubsystem> cluster = startThreeNodeCluster("g", genesis);
        AppChainSubsystem nodeA = cluster.get(0);
        AppChainSubsystem nodeB = cluster.get(1);
        AppChainSubsystem nodeC = cluster.get(2);

        nodeA.setThreshold(3);
        nodeB.setThreshold(3);
        awaitTrue("threshold 3 scheduled on every member",
                () -> cluster.stream().allMatch(node -> node.effectiveThreshold() == 3));
        long thresholdFrom = (long) nodeA.status().get("membershipEpochFromHeight");
        for (int i = 0; nodeC.tipHeight() < thresholdFrom; i++) {
            awaitFinalizedTraffic(nodeA, cluster, "threshold-lag-" + i);
        }

        // 3-of-3 is active: the add now needs all three approvals
        nodeA.addMember(pubD);
        nodeB.addMember(pubD);
        nodeC.addMember(pubD);
        awaitTrue("add-D scheduled on every member",
                () -> cluster.stream().allMatch(node -> node.members().contains(pubD)));
        long addFrom = (long) nodeA.status().get("membershipEpochFromHeight");
        for (int i = 0; nodeC.tipHeight() < addFrom + 2; i++) {
            awaitFinalizedTraffic(nodeA, cluster, "add-lag-" + i);
        }

        for (AppChainSubsystem node : cluster) {
            assertThat(node.members()).containsExactlyInAnyOrder(pubA, pubB, pubC, pubD);
            assertThat(node.status())
                    .containsEntry("membershipActiveMembers", 4)
                    .containsEntry("membershipActiveThreshold", 3);
            assertThat(node.stateRoot()).isEqualTo(nodeA.stateRoot());
        }
    }

    /**
     * A message from a member whose epoch is scheduled but not yet active is
     * pooled by the existing members (gossip admits scheduled members). Before
     * the fix the leader included it below the activation height, every
     * follower rejected the proposal, and the chain stalled view after view
     * until the envelope expired. The leader now keeps it pooled and includes
     * it only once its sender is a member at the candidate height.
     */
    @Test
    void scheduledMembersMessage_waitsForActivation_andChainStaysLive() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> genesis = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();
        AppChainSubsystem nodeA = start("sa", KEY_A, genesis, portA, List.of(peer(portB)), 600);
        AppChainSubsystem nodeB = start("sb", KEY_B, genesis, portB, List.of(peer(portA)), 600);
        awaitTrue("connected", () -> connected(nodeA) && connected(nodeB));
        List<AppChainSubsystem> members = List.of(nodeA, nodeB);

        nodeA.addMember(pubC); // 2-of-2 plus one member is a valid 2-of-3
        nodeB.addMember(pubC);
        awaitTrue("add-C scheduled on both", () -> members.stream()
                .allMatch(node -> node.members().contains(pubC)));
        long activation = (long) nodeA.status().get("membershipEpochFromHeight");

        // C's early message reaches both members' pools, as gossip from C would
        AppMessage early = signedBy(KEY_C, "t", "early-from-c".getBytes(StandardCharsets.UTF_8), 1);
        nodeA.onInboundMessages(List.of(early));
        nodeB.onInboundMessages(List.of(early));

        // The chain keeps finalizing existing members' traffic below the activation height
        for (int i = 0; i < 3; i++) {
            long height = awaitFinalizedTraffic(nodeA, members, "before-activation-" + i);
            assertThat(height).isLessThan(activation);
        }
        assertThat(nodeA.messageHeight(early.getMessageId())).isEmpty();

        // C's own node refuses local submissions until its epoch is active
        AppChainSubsystem nodeC = start("sc", KEY_C, genesis, 0, List.of(peer(portA)), 600);
        assertThatThrownBy(() -> nodeC.submit("t", "too-early".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an active member");

        // C's pooled message is finalized only once C is a member at that height
        for (int i = 0; nodeA.messageHeight(early.getMessageId()).isEmpty(); i++) {
            awaitFinalizedTraffic(nodeA, members, "toward-activation-" + i);
        }
        assertThat(nodeA.messageHeight(early.getMessageId())).hasValueSatisfying(height ->
                assertThat(height).isGreaterThanOrEqualTo(activation));
        awaitTrue("B finalized C's message", () -> nodeB.messageHeight(early.getMessageId()).isPresent());
        assertThat(nodeB.stateRoot()).isEqualTo(nodeA.stateRoot());
    }

    @Test
    void halfApprovedCommand_expiresAfterWindow() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> genesis = Set.of(pubA, pubB);
        int portA = freePort();
        int portB = freePort();

        // Tiny approval window: 2 blocks
        AppChainSubsystem nodeA = start("ea", KEY_A, genesis, portA, List.of(peer(portB)), 2);
        AppChainSubsystem nodeB = start("eb", KEY_B, genesis, portB, List.of(peer(portA)), 2);
        awaitTrue("connected", () -> connected(nodeA) && connected(nodeB));

        nodeA.addMember(pubC); // 1 of 2 approvals
        awaitTrue("first approval finalized", () -> nodeA.tipHeight() >= 1);

        // Let the window pass with ordinary blocks
        for (int i = 0; i < 3; i++) {
            String id = nodeA.submit("t", ("filler-" + i).getBytes(StandardCharsets.UTF_8));
            awaitTrue("filler " + i, () -> nodeA.messageHeight(HexUtil.decodeHexString(id)).isPresent());
        }

        // B's approval comes too late — the count restarted, still 1 of 2
        nodeB.addMember(pubC);
        awaitTrue("late approval finalized", () -> nodeB.tipHeight() >= 5);
        String id = nodeA.submit("t", "check".getBytes(StandardCharsets.UTF_8));
        awaitTrue("chain alive", () -> nodeB.messageHeight(HexUtil.decodeHexString(id)).isPresent());

        assertThat(nodeA.members()).containsExactlyInAnyOrder(pubA, pubB);
        assertThat(nodeB.members()).containsExactlyInAnyOrder(pubA, pubB);
    }

    // ------------------------------------------------------------------

    /** Fully meshed governed 2-of-3 chain on KEY_A..KEY_C (A is the fixed proposer). */
    private List<AppChainSubsystem> startThreeNodeCluster(String prefix, Set<String> genesis)
            throws Exception {
        int portA = freePort();
        int portB = freePort();
        int portC = freePort();
        AppChainSubsystem nodeA = start(prefix + "a", KEY_A, genesis, portA,
                List.of(peer(portB), peer(portC)), 600);
        AppChainSubsystem nodeB = start(prefix + "b", KEY_B, genesis, portB,
                List.of(peer(portA), peer(portC)), 600);
        AppChainSubsystem nodeC = start(prefix + "c", KEY_C, genesis, portC,
                List.of(peer(portA), peer(portB)), 600);
        List<AppChainSubsystem> cluster = List.of(nodeA, nodeB, nodeC);
        awaitTrue("cluster connected", () -> cluster.stream().allMatch(
                GovernedMembershipIntegrationTest::connected));
        return cluster;
    }

    /** An ordinary app message signed by {@code seed}, as that member's node would gossip it. */
    private static AppMessage signedBy(byte[] seed, String topic, byte[] body, long senderSeq) {
        AppMessageSigner signer = new AppMessageSigner(HexUtil.encodeHexString(seed));
        long expiresAt = System.currentTimeMillis() / 1000 + 600;
        byte[] signedBody = AppMessage.signedBodyBytes(CHAIN_ID, topic, signer.publicKey(),
                senderSeq, expiresAt, body);
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(CHAIN_ID, topic, signer.publicKey(),
                        senderSeq, expiresAt, body))
                .chainId(CHAIN_ID)
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(senderSeq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }

    private static void assertQuorumRejected(ThrowingCallable change) {
        assertThatThrownBy(change)
                .isInstanceOfSatisfying(MembershipChangeRejectedException.class, rejected ->
                        assertThat(rejected.code()).isEqualTo(MembershipChangeRejectedException.QUORUM_INVALID));
    }

    /** Submit one message and wait until every node finalized it; returns its height. */
    private static long awaitFinalizedTraffic(AppChainSubsystem submitter,
                                              List<AppChainSubsystem> cluster,
                                              String label) throws InterruptedException {
        byte[] id = HexUtil.decodeHexString(
                submitter.submit("t", label.getBytes(StandardCharsets.UTF_8)));
        awaitTrue("finalized " + label, () -> cluster.stream()
                .allMatch(node -> node.messageHeight(id).isPresent()));
        return submitter.messageHeight(id).orElseThrow();
    }

    private AppChainSubsystem start(String name, byte[] key, Set<String> members, int serverPort,
                                    List<AppChainConfig.AppPeer> peers, long approvalWindow)
            throws Exception {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(key))
                .stateCommitmentIdentity(AppChainIntegrationFixtures.MPF)
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(pubHex(KEY_A))
                .threshold(2)
                .blockIntervalMs(500)
                .pluginSettings(Map.of(
                        "membership.mode", "governed",
                        "membership.approval-window-blocks", String.valueOf(approvalWindow)))
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                tempDir.resolve("ledger-" + name).toString(), null, log);
        nodes.add(subsystem);

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
        long deadline = System.currentTimeMillis() + 60_000;
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
