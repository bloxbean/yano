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
import org.yanoproject.api.appchain.AppSubmissionRejectedException;
import org.yanoproject.api.appchain.MembershipChangeRejectedException;
import org.yanoproject.api.appchain.schema.CborSchema;
import org.yanoproject.api.appchain.topics.TopicCommandRules;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicPolicyProof;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.util.StrictEd25519;
import org.yanoproject.appchain.proof.TopicPolicyProofVerifier;
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
    /** Long enough that governance can override a pending change before it activates (V6 step 6). */
    private static final long SHOWCASE_LAG = 10;

    @TempDir
    Path tempDir;

    private final List<NodeServer> servers = new ArrayList<>();
    private final List<AppChainSubsystem> nodes = new ArrayList<>();
    /** Server ports of the topic cluster's A, B and C. */
    private int[] topicPorts;

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
        nodeA.submitGovernance(GovernedMembership.TOPIC, command);
        nodeB.submitGovernance(GovernedMembership.TOPIC, command);
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
        nodeA.submitGovernance(GovernedMembership.TOPIC, add);
        nodeB.submitGovernance(GovernedMembership.TOPIC, add);
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

    /**
     * ADR app-layer/039 M6 (V6 core): topics enabled at genesis on a governed 2-of-3 chain. Governance
     * creates a namespace at the consensus threshold, its owner creates a protected child topic, a framed
     * write finalizes on every member with the verified writer, unframed, forged and replayed writes are
     * refused at ingress, and a member that was down catches up through the protected writes it missed.
     */
    @Test
    void topicRegistryGovernsProtectedWritesAcrossMembers() throws Exception {
        Map<String, String> topics = Map.of("topics.mode", "enabled", "topics.activation-lag", "1");
        List<AppChainSubsystem> cluster = startTopicCluster(topics);
        AppChainSubsystem nodeA = cluster.get(0);
        AppChainSubsystem nodeB = cluster.get(1);
        AppChainSubsystem nodeC = cluster.get(2);
        String generation = generation(nodeA);
        byte[] owner = seed(81);
        byte[] writer = seed(82);

        // Block 1 is ungoverned by design (D1): the seed governs from block 2.
        awaitFinalizedTraffic(nodeA, cluster, "genesis");
        assertThatThrownBy(() -> nodeA.submit("loose", new byte[] {1}))
                .isInstanceOfSatisfying(AppSubmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("TOPIC_UNREGISTERED"));

        byte[] governance = governance(generation, 0,
                new TopicWire.GovCreate("acme/", keys(owner), Policy.INHERIT, null));
        nodeA.submitTopicGovernance(governance);
        nodeB.submitTopicGovernance(governance);
        awaitTrue("acme/ active everywhere", () -> cluster.stream().allMatch(node -> active(node, "acme/")));

        TopicWire.CommandStatement child = new TopicWire.CommandStatement(CHAIN_ID, generation, "acme/orders/",
                "acme/", null, "01".repeat(16), System.currentTimeMillis() + 600_000, new TopicWire.Create(
                keys(owner), new Policy(new Writers(keys(writer)), null, null, null, null), null));
        nodeB.submit(TopicCommandRules.TOPIC, signed(child, owner));
        awaitTrue("acme/orders/ active everywhere",
                () -> cluster.stream().allMatch(node -> active(node, "acme/orders/")));

        byte[] frame = frame(generation, "acme/orders/new", new byte[] {7}, writer);
        byte[] id = HexUtil.decodeHexString(nodeA.submit("acme/orders/new", frame));
        awaitTrue("protected write finalized everywhere",
                () -> cluster.stream().allMatch(node -> node.messageHeight(id).isPresent()));
        assertRefused(() -> nodeB.submit("acme/orders/new", frame), "TOPIC_PROOF_REPLAYED");
        assertRefused(() -> nodeB.submit("acme/orders/new", new byte[] {7}), "TOPIC_FRAME_INVALID");
        assertRefused(() -> nodeB.submit("acme/orders/new",
                frame(generation, "acme/orders/new", new byte[] {8}, owner)), "TOPIC_WRITER_DENIED");

        nodeC.stop();
        nodes.remove(nodeC);
        byte[] whileDown = HexUtil.decodeHexString(nodeA.submit("acme/orders/new",
                frame(generation, "acme/orders/new", new byte[] {9}, writer)));
        awaitTrue("write finalized while C is down", () -> nodeA.messageHeight(whileDown).isPresent());
        AppChainSubsystem restarted = restartC(nodeA, nodeC, topics);
        long revision = restarted.readTopics(state -> state.entry("acme/orders/").orElseThrow().active().revision());
        assertThat(revision).isEqualTo(1L);
    }

    /**
     * ADR app-layer/039 V6 showcase on a 3-member governed cluster with the production profile (D4: governance
     * only, no registrars, unregistered topics refused). A block carrying a forged write is rejected by the
     * transition itself (TopicRegistryKernelTest); here every refusal happens at ingress.
     */
    @Test
    void topicShowcaseRunsEndToEnd() throws Exception {
        List<AppChainSubsystem> cluster = startTopicCluster(
                Map.of("topics.mode", "enabled", "topics.activation-lag", String.valueOf(SHOWCASE_LAG)));
        AppChainSubsystem nodeA = cluster.get(0);
        AppChainSubsystem nodeB = cluster.get(1);
        AppChainSubsystem nodeC = cluster.get(2);
        awaitFinalizedTraffic(nodeA, cluster, "genesis");
        String generation = generation(nodeA);
        byte[] owner = seed(91);
        byte[] recovered = seed(92);
        byte[] ordersOwner = seed(93);
        byte[] rotatedOrdersOwner = seed(94);
        byte[] issuer = seed(95);
        byte[] session = seed(96);
        long now = System.currentTimeMillis();

        // (1) one member's vote alone creates nothing; the second reaches the consensus threshold
        byte[] createAcme = governance(generation, 1, new TopicWire.GovCreate("acme/", keys(owner),
                new Policy(new Writers(keys(owner)), null, null, null, null), null));
        byte[] firstVote = HexUtil.decodeHexString(nodeA.submitTopicGovernance(createAcme));
        awaitTrue("first vote finalized", () -> nodeA.messageHeight(firstVote).isPresent());
        boolean createdByOneVote = nodeA.readTopics(state -> state.entry("acme/").isPresent());
        assertThat(createdByOneVote).isFalse();
        nodeB.submitTopicGovernance(createAcme);
        awaitWithBlocks("acme/ active everywhere", nodeC,
                () -> cluster.stream().allMatch(node -> active(node, "acme/")));

        // (2) the owners create acme/orders with a writer policy and a schema, then rotate its owners
        byte[] schemaIr = CborSchema.of(CborSchema.IntegerNode.uint()).definition();
        Policy orders = new Policy(new Writers(keys(issuer)), List.of(pubHex(issuer)), null,
                TopicWireCodec.blake2b256Hex(schemaIr), null);
        nodeC.submit(TopicCommandRules.TOPIC, signed(new TopicWire.CommandStatement(CHAIN_ID, generation,
                "acme/orders", "acme/", null, nonce(2), now + 600_000,
                new TopicWire.Create(keys(ordersOwner), orders, schemaIr)), owner));
        awaitWithBlocks("acme/orders active everywhere", nodeC,
                () -> cluster.stream().allMatch(node -> active(node, "acme/orders")));
        long ordersHead = nodeA.readTopics(state -> state.entry("acme/orders").orElseThrow().head());
        nodeA.submit(TopicCommandRules.TOPIC, signed(new TopicWire.CommandStatement(CHAIN_ID, generation,
                "acme/orders", "acme/orders", ordersHead, nonce(3), now + 600_000,
                new TopicWire.Rotate(keys(rotatedOrdersOwner))), ordersOwner));
        awaitWithBlocks("acme/orders owners rotated", nodeC, () -> cluster.stream().allMatch(node ->
                node.readTopics(state -> state.entry("acme/orders").orElseThrow().active().content().owners()
                        .equals(keys(rotatedOrdersOwner)))));

        // (3) the issuer delegates a session key, which writes directly to any member
        TopicWire.Delegation delegation = delegation(generation, issuer, session, "acme/orders",
                now - 60_000, now + 1_800_000);
        byte[] write = delegatedFrame(generation, "acme/orders", new byte[] {0x07}, 4, delegation, session);
        byte[] writeId = HexUtil.decodeHexString(nodeB.submit("acme/orders", write));
        awaitTrue("delegated write finalized everywhere",
                () -> cluster.stream().allMatch(node -> node.messageHeight(writeId).isPresent()));

        // (4) forged, misdirected, replayed and schema-violating writes are refused
        assertRefused(() -> nodeC.submit("acme/orders", frame(generation, "acme/orders", new byte[] {0x07},
                seed(97))), "TOPIC_WRITER_DENIED");
        assertRefused(() -> nodeC.submit("acme/other", delegatedFrame(generation, "acme/other", new byte[] {0x07},
                5, delegation, session)), "TOPIC_DELEGATION_INVALID");
        assertRefused(() -> nodeC.submit("acme/orders", write), "TOPIC_PROOF_REPLAYED");
        assertRefused(() -> nodeC.submit("acme/orders", delegatedFrame(generation, "acme/orders",
                new byte[] {0x60}, 6, delegation, session)), "TOPIC_SCHEMA_REJECTED");

        // (5) the issuer revokes the session key; its next write is refused once the revocation is final
        byte[] revoke = HexUtil.decodeHexString(nodeA.submit(TopicCommandRules.TOPIC, signed(
                new TopicWire.CommandStatement(CHAIN_ID, generation, "acme/orders", TopicWire.AUTHORITY_ISSUER, null,
                        nonce(7), now + 600_000, new TopicWire.Revoke(delegation)), issuer)));
        awaitTrue("revocation final everywhere",
                () -> cluster.stream().allMatch(node -> node.messageHeight(revoke).isPresent()));
        assertRefused(() -> nodeB.submit("acme/orders", delegatedFrame(generation, "acme/orders",
                new byte[] {0x08}, 8, delegation, session)), "TOPIC_DELEGATION_INVALID");

        // (6) the owners "lose" their keys: governance replaces them and cancels their pending change
        long acmeHead = nodeA.readTopics(state -> state.entry("acme/").orElseThrow().head());
        String stale = nodeA.submit(TopicCommandRules.TOPIC, signed(new TopicWire.CommandStatement(CHAIN_ID,
                generation, "acme/", "acme/", acmeHead, nonce(9), now + 600_000,
                new TopicWire.Update(new Policy(Writers.OPEN, null, null, null, null), null)), owner));
        awaitTrue("old owners' change pending",
                () -> nodeA.readTopics(state -> state.entry("acme/").orElseThrow().pending() > 0));
        byte[] recover = governance(generation, 10, new TopicWire.GovRecover("acme/", keys(recovered)));
        nodeA.submitTopicGovernance(recover);
        nodeB.submitTopicGovernance(recover);
        awaitWithBlocks("acme/ recovered everywhere", nodeC, () -> cluster.stream().allMatch(node ->
                node.readTopics(state -> state.entry("acme/").orElseThrow().active().content().owners()
                        .equals(keys(recovered)))));
        String staleReceipt = nodeC.readTopics(state -> state.receipt(stale).orElseThrow().code());
        assertThat(staleReceipt).isEqualTo("TOPIC_COMMAND_SUPERSEDED");
        boolean openedByStaleChange = nodeC.readTopics(state -> state.entry("acme/").orElseThrow().active()
                .content().policy().writers().open());
        assertThat(openedByStaleChange).as("the cancelled change never governs").isFalse();

        // (7) offline: the effective policy of the write's height, proven against another member's root
        long height = nodeA.messageHeight(writeId).orElseThrow();
        TopicPolicyProof proof = nodeA.topicPolicyProof("acme/orders", height).orElseThrow();
        byte[] anchoredRoot = nodeC.stateProofEnvelopeAtHeight(height - 1, TopicKeys.config()).orElseThrow()
                .proof().snapshot().stateRoot();
        TopicWire.EffectiveOutcome outcome = TopicPolicyProofVerifier.verify(proof, anchoredRoot).orElseThrow();
        assertThat(outcome.kind()).isEqualTo(TopicWire.OutcomeKind.POLICY);
        assertThat(outcome.policy().delegate()).containsExactly(pubHex(issuer));
        assertThat(nodeB.messageInclusionProof(writeId).orElseThrow().verifiesRoot()).isTrue();
        // authorship: the session key signed the write statement under the issuer's delegation
        TopicWire.Frame frame = TopicWireCodec.decodeFrame(write);
        byte[] statement = TopicWireCodec.encodeWriteStatement(new TopicWire.WriteStatement(CHAIN_ID, generation,
                "acme/orders", TopicWireCodec.blake2b256Hex(frame.payload()), frame.proof().nonce(),
                frame.proof().notAfter(), TopicWireCodec.delegationHash(CHAIN_ID, generation, delegation)));
        assertThat(StrictEd25519.verify(HexUtil.decodeHexString(pubHex(session)),
                frame.proof().signatures().getFirst().sig(), statement)).isTrue();
        assertThat(StrictEd25519.verify(HexUtil.decodeHexString(delegation.issuer()), delegation.sig(),
                TopicWireCodec.delegationSignedBytes(CHAIN_ID, generation, delegation))).isTrue();
    }

    /**
     * ADR app-layer/039 D1, M6: governance enables topics on a running chain. A member restarted with
     * {@code topics.mode: enabled} in its YAML before the vote computes the same state (the seed is read only
     * for block 1), and a member restarted after it agrees.
     */
    @Test
    void governanceEnablesTopicsOnARunningChain() throws Exception {
        List<AppChainSubsystem> started = startTopicCluster(Map.of());
        AppChainSubsystem nodeA = started.get(0);
        AppChainSubsystem nodeB = started.get(1);
        awaitFinalizedTraffic(nodeA, started, "before topics");
        byte[] loose = HexUtil.decodeHexString(nodeB.submit("loose", new byte[] {1}));
        awaitTrue("unregistered write finalized", () -> nodeA.messageHeight(loose).isPresent());
        AppChainSubsystem seeded = restartC(nodeA, started.get(2), Map.of("topics.mode", "enabled"));
        List<AppChainSubsystem> cluster = List.of(nodeA, nodeB, seeded);

        byte[] enable = governance(generation(nodeA), 1, new TopicWire.GovConfigure(new TopicWire.Settings(true,
                null, false, true, 3_600_000, 604_800_000, 4, 100_000, 1_000, 2, 600)));
        nodeA.submitTopicGovernance(enable);
        nodeB.submitTopicGovernance(enable);
        awaitWithBlocks("topics enabled everywhere", nodeA, () -> cluster.stream().allMatch(node ->
                node.readTopics(state -> state.config().isPresent())));
        assertRefused(() -> nodeB.submit("loose", new byte[] {2}), "TOPIC_UNREGISTERED");
        awaitTrue("roots agree", () -> cluster.stream().allMatch(node -> node.tipHeight() == nodeA.tipHeight()
                && Arrays.equals(node.stateRoot(), nodeA.stateRoot())));

        AppChainSubsystem restarted = restartC(nodeA, seeded, Map.of());
        long configRevision = restarted.readTopics(state -> state.config().orElseThrow().revision());
        assertThat(configRevision).isEqualTo(1L);
    }

    /**
     * Waits for a registry activation. Activations happen at block heights and an idle chain produces no
     * blocks, so this keeps blocks coming: each nudge is one member's vote that never reaches the threshold
     * (beyond that member's share of open commands it is refused with a receipt, which still makes a block).
     * Nudge through a member that casts no real vote later, or its votes would hit its share.
     */
    private static void awaitWithBlocks(String what, AppChainSubsystem node, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        for (int nudge = 0; System.currentTimeMillis() < deadline; nudge++) {
            if (condition.getAsBoolean()) {
                return;
            }
            node.submitTopicGovernance(governance(generation(node), 10_000 + nudge,
                    new TopicWire.GovRetire("nudge/")));
            Thread.sleep(500);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    /** Governed 2-of-3 cluster on KEY_A..KEY_C (A proposes) with the given topic settings. */
    private List<AppChainSubsystem> startTopicCluster(Map<String, String> topics) throws Exception {
        Set<String> genesis = Set.of(pubHex(KEY_A), pubHex(KEY_B), pubHex(KEY_C));
        topicPorts = new int[] {freePort(), freePort(), freePort()};
        AppChainSubsystem nodeA = start("a", KEY_A, genesis, topicPorts[0],
                List.of(peer(topicPorts[1]), peer(topicPorts[2])), 600, topics);
        AppChainSubsystem nodeB = start("b", KEY_B, genesis, topicPorts[1],
                List.of(peer(topicPorts[0]), peer(topicPorts[2])), 600, topics);
        AppChainSubsystem nodeC = start("c", KEY_C, genesis, topicPorts[2],
                List.of(peer(topicPorts[0]), peer(topicPorts[1])), 600, topics);
        List<AppChainSubsystem> cluster = List.of(nodeA, nodeB, nodeC);
        awaitTrue("cluster connected", () -> cluster.stream().allMatch(GovernedMembershipIntegrationTest::connected));
        return cluster;
    }

    /** Restart C on its own ledger and wait until it has caught up with A. */
    private AppChainSubsystem restartC(AppChainSubsystem nodeA, AppChainSubsystem nodeC, Map<String, String> topics)
            throws Exception {
        if (nodes.remove(nodeC)) {
            nodeC.stop();
        }
        AppChainSubsystem restarted = start("c", KEY_C, Set.of(pubHex(KEY_A), pubHex(KEY_B), pubHex(KEY_C)), 0,
                List.of(peer(topicPorts[0]), peer(topicPorts[1])), 600, topics);
        awaitTrue("restarted C caught up", () -> restarted.tipHeight() == nodeA.tipHeight()
                && Arrays.equals(restarted.stateRoot(), nodeA.stateRoot()));
        return restarted;
    }

    private static String generation(AppChainSubsystem node) {
        return HexUtil.encodeHexString(node.stateCommitmentIdentity().orElseThrow().digest());
    }

    private static byte[] governance(String generation, int nonce, TopicWire.GovernanceOperation operation) {
        return TopicWireCodec.encodeGovernanceCommand(
                new TopicWire.GovernanceCommand(CHAIN_ID, generation, nonce(nonce), operation));
    }

    private static String nonce(int value) {
        return String.format("%032x", value);
    }

    private static TopicWire.Delegation delegation(String generation, byte[] issuer, byte[] delegate, String scope,
                                                   long notBefore, long notAfter) {
        TopicWire.Delegation unsigned = new TopicWire.Delegation(pubHex(delegate), scope, notBefore, notAfter,
                pubHex(issuer), new byte[64]);
        byte[] signed = TopicWireCodec.delegationSignedBytes(CHAIN_ID, generation, unsigned);
        return new TopicWire.Delegation(unsigned.delegate(), scope, notBefore, notAfter, unsigned.issuer(),
                new AppMessageSigner(HexUtil.encodeHexString(issuer)).sign(signed));
    }

    /** A write signed by the session key under {@code delegation}. */
    private static byte[] delegatedFrame(String generation, String topic, byte[] payload, int nonce,
                                         TopicWire.Delegation delegation, byte[] sessionKey) {
        long notAfter = System.currentTimeMillis() + 600_000;
        byte[] statement = TopicWireCodec.encodeWriteStatement(new TopicWire.WriteStatement(CHAIN_ID, generation,
                topic, TopicWireCodec.blake2b256Hex(payload), nonce(nonce), notAfter,
                TopicWireCodec.delegationHash(CHAIN_ID, generation, delegation)));
        return TopicWireCodec.encodeFrame(new TopicWire.Frame(payload, new TopicWire.WriterProof(nonce(nonce),
                notAfter, List.of(new TopicWire.Signature(pubHex(sessionKey),
                new AppMessageSigner(HexUtil.encodeHexString(sessionKey)).sign(statement))), delegation)));
    }

    private static boolean active(AppChainSubsystem node, String name) {
        return node.readTopics(state -> state.entry(name).map(record -> record.active() != null).orElse(false));
    }

    private static void assertRefused(ThrowingCallable submit, String code) {
        assertThatThrownBy(submit).isInstanceOfSatisfying(AppSubmissionRejectedException.class,
                rejected -> assertThat(rejected.code()).isEqualTo(code));
    }

    private static TopicWire.KeySet keys(byte[] privateKey) {
        return new TopicWire.KeySet(1, List.of(pubHex(privateKey)));
    }

    private static byte[] signed(TopicWire.CommandStatement statement, byte[] key) {
        byte[] bytes = TopicWireCodec.encodeCommandStatement(statement);
        return TopicWireCodec.encodeRegistryCommand(new TopicWire.RegistryCommand(statement, List.of(
                new TopicWire.Signature(pubHex(key), new AppMessageSigner(HexUtil.encodeHexString(key)).sign(bytes)))));
    }

    private static byte[] frame(String generation, String topic, byte[] payload, byte[] key) {
        long notAfter = System.currentTimeMillis() + 600_000;
        byte[] statement = TopicWireCodec.encodeWriteStatement(new TopicWire.WriteStatement(CHAIN_ID, generation,
                topic, TopicWireCodec.blake2b256Hex(payload), "02".repeat(16), notAfter, null));
        return TopicWireCodec.encodeFrame(new TopicWire.Frame(payload, new TopicWire.WriterProof("02".repeat(16),
                notAfter, List.of(new TopicWire.Signature(pubHex(key),
                new AppMessageSigner(HexUtil.encodeHexString(key)).sign(statement))), null)));
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
        return start(name, key, members, serverPort, peers, approvalWindow, Map.of());
    }

    private AppChainSubsystem start(String name, byte[] key, Set<String> members, int serverPort,
                                    List<AppChainConfig.AppPeer> peers, long approvalWindow,
                                    Map<String, String> extraSettings)
            throws Exception {
        Map<String, String> settings = new HashMap<>(extraSettings);
        settings.put("membership.mode", "governed");
        settings.put("membership.approval-window-blocks", String.valueOf(approvalWindow));
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(key))
                .stateCommitmentIdentity(AppChainIntegrationFixtures.MPF)
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(pubHex(KEY_A))
                .threshold(2)
                .blockIntervalMs(500)
                .pluginSettings(settings)
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
