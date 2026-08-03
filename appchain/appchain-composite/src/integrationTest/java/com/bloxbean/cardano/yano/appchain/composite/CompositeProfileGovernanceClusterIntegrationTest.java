package com.bloxbean.cardano.yano.appchain.composite;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainMembershipEpoch;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.appchain.composite.contracts.AggregateQueryLimitsV1;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;
import com.bloxbean.cardano.yano.appchain.testkit.AppChainTestProfiles;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(180)
class CompositeProfileGovernanceClusterIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(
            CompositeProfileGovernanceClusterIntegrationTest.class);
    private static final long MAGIC = 42;
    private static final String CHAIN = "profile-governance-cluster";
    private static final byte[] KEY_A = seed(81);
    private static final byte[] KEY_B = seed(82);
    private static final byte[] KEY_C = seed(83);
    private static final ComponentDescriptor OLD_DESCRIPTOR = descriptor(
            "1.0.0", "records.v1");
    private static final ComponentDescriptor NEXT_DESCRIPTOR = descriptor(
            "records", "2.0.0", "records-state-v1", "records.v2", 1);
    private static final ComponentDescriptor AUDIT_DESCRIPTOR = descriptor(
            "audit", "1.0.0", "audit-state-v1", "audit.v1", 1);
    private static final WorkflowDescriptor NEXT_WORKFLOW = new WorkflowDescriptor(
            "records-audit", "1.0.0", "records.workflow.v1", 1, 0,
            List.of(NEXT_DESCRIPTOR.generation(), AUDIT_DESCRIPTOR.generation()), 1);
    private static final CompositeProfile OLD_PROFILE = CompositeProfile.of(
            "records-direct", "1", List.of(OLD_DESCRIPTOR));
    private static final CompositeProfile NEXT_PROFILE = new CompositeProfile(
            1, "records-gated", "2",
            List.of(NEXT_DESCRIPTOR, AUDIT_DESCRIPTOR), List.of(NEXT_WORKFLOW),
            List.of(), AggregateQueryLimitsV1.DEFAULT);
    private static final Map<String, String> SETTINGS = Map.of(
            "effects.enabled", "true",
            "effects.max-per-block", "4",
            "effects.result-window-blocks", "3",
            "membership.mode", "governed",
            "machines.composite.profile-mode", "governed",
            "machines.composite.profile-governance.min-activation-lag", "2",
            "machines.composite.profile-governance.proposal-ttl-blocks", "40",
            "machines.composite.profile-governance.max-epochs", "10");

    @TempDir
    Path tempDir;

    private final Map<String, AppChainSubsystem> nodes = new LinkedHashMap<>();
    private final Map<String, NodeServer> servers = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        new ArrayList<>(nodes.values()).forEach(node -> {
            try { node.stop(); } catch (Exception ignored) { }
        });
        new ArrayList<>(servers.values()).forEach(server -> {
            try { server.shutdown(); } catch (Exception ignored) { }
        });
    }

    @Test
    void threeMembersDeployFirstThenActivateAndRestartMissingMember() throws Exception {
        String pubA = pubHex(KEY_A);
        String pubB = pubHex(KEY_B);
        String pubC = pubHex(KEY_C);
        Set<String> members = Set.of(pubA, pubB, pubC);
        AppChainMembershipEpoch membership = new AppChainMembershipEpoch(
                0, List.of(pubA, pubB, pubC), 2);
        List<Integer> ports = List.of(freePort(), freePort(), freePort());

        AppChainSubsystem nodeA = start("a", KEY_A, members, pubA, ports, 0, true, membership);
        AppChainSubsystem nodeB = start("b", KEY_B, members, pubA, ports, 1, true, membership);
        AppChainSubsystem nodeC = start("c", KEY_C, members, pubA, ports, 2, false, membership);
        await("full mesh", () -> nodes.values().stream().allMatch(
                node -> connected(node, 2)));

        long activationHeight = 20;
        byte[] proposalId = seed(91);
        CompositeProfileGovernanceV1.Begin begin = new CompositeProfileGovernanceV1.Begin(
                proposalId, OLD_PROFILE.digest(), membership.digest(), NEXT_PROFILE.digest(),
                NEXT_PROFILE.canonicalBytes().length, 1, activationHeight, 30);
        byte[] proposalHash = CompositeProfileGovernanceV1.proposalHash(CHAIN, begin);

        finalizeCommand(nodeA, begin, nodeA, nodeB, nodeC);
        finalizeCommand(nodeA, new CompositeProfileGovernanceV1.Chunk(
                proposalId, 0, NEXT_PROFILE.canonicalBytes()), nodeA, nodeB, nodeC);
        finalizeCommand(nodeA, new CompositeProfileGovernanceV1.Seal(proposalId),
                nodeA, nodeB, nodeC);
        finalizeCommand(nodeA, new CompositeProfileGovernanceV1.Approve(proposalHash),
                nodeA, nodeB, nodeC);
        finalizeCommand(nodeB, new CompositeProfileGovernanceV1.Approve(proposalHash),
                nodeA, nodeB, nodeC);
        finalizeCommand(nodeA, new CompositeProfileGovernanceV1.Ready(
                proposalHash, NEXT_PROFILE.digest()), nodeA, nodeB, nodeC);
        finalizeCommand(nodeB, new CompositeProfileGovernanceV1.Ready(
                proposalHash, NEXT_PROFILE.digest()), nodeA, nodeB, nodeC);

        assertThat(proposalStatus(nodeA)).isEqualTo("SEALED");
        assertThat(proposalStatus(nodeC)).isEqualTo("SEALED");
        assertThat(machineStatus(nodeC).get("locallyReady")).isEqualTo(false);
        AppChainSubsystem missingCatalogNode = nodeC;
        assertThatThrownBy(() -> missingCatalogNode.validatePrivilegedSystemMessage(
                CompositeProfileGovernanceV1.TOPIC,
                new CompositeProfileGovernanceV1.Ready(
                        proposalHash, NEXT_PROFILE.digest()).encode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent from the local executable catalog");

        stop("c");
        nodeC = start("c", KEY_C, members, pubA, ports, 2, true, membership);
        AppChainSubsystem restartedC = nodeC;
        await("restarted member catch-up", () -> restartedC.tipHeight() == nodeA.tipHeight()
                && Arrays.equals(restartedC.stateRoot(), nodeA.stateRoot()));

        finalizeCommand(restartedC, new CompositeProfileGovernanceV1.Ready(
                proposalHash, NEXT_PROFILE.digest()), nodeA, nodeB, restartedC);
        await("proposal scheduled", () -> "SCHEDULED".equals(proposalStatus(nodeA))
                && "SCHEDULED".equals(proposalStatus(nodeB))
                && "SCHEDULED".equals(proposalStatus(restartedC)));

        while (nodeA.tipHeight() < activationHeight - 1) {
            finalizeBusiness(nodeA, "records.v1", nodeA, nodeB, restartedC);
        }
        assertThat(activeDigest(nodeA)).isEqualTo(HexUtil.encodeHexString(OLD_PROFILE.digest()));
        finalizeBusiness(nodeA, "records.v2", nodeA, nodeB, restartedC);
        await("profile activated", () -> nodeA.tipHeight() >= activationHeight
                && currentEpoch(nodeA) == 1 && currentEpoch(nodeB) == 1
                && currentEpoch(restartedC) == 1);

        assertThat(activeDigest(nodeA)).isEqualTo(HexUtil.encodeHexString(NEXT_PROFILE.digest()));
        assertThat(nodeA.stateRoot()).containsExactly(nodeB.stateRoot())
                .containsExactly(restartedC.stateRoot());

        // One live epoch transition covers all ADR-015 evolution shapes:
        // compatible replacement, direct-route closure, component add,
        // workflow add/change, and quota rebalance (1 -> 3, with one drained
        // old-generation quota still fitting the framework cap of 4).
        byte[] recordsBeforeClosedRoute = nodeA.query(
                "components/records/get", new byte[0]).payload();
        byte[] closedRouteMessage = HexUtil.decodeHexString(
                nodeA.submit("records.v1", new byte[]{1}));
        finalizeBusiness(nodeA, "audit.v1", nodeA, nodeB, restartedC);
        assertThat(nodeA.messageHeight(closedRouteMessage)).isEmpty();
        assertThat(nodeB.messageHeight(closedRouteMessage)).isEmpty();
        assertThat(restartedC.messageHeight(closedRouteMessage)).isEmpty();
        assertThat(nodeA.query("components/records/get", new byte[0]).payload())
                .containsExactly(recordsBeforeClosedRoute);
        finalizeBusiness(nodeA, "records.workflow.v1", nodeA, nodeB, restartedC);
        assertThat(nodeA.stateRoot()).containsExactly(nodeB.stateRoot())
                .containsExactly(restartedC.stateRoot());

        stop("b");
        AppChainSubsystem restartedB = start(
                "b", KEY_B, members, pubA, ports, 1, true, membership);
        await("post-activation restart catch-up", () -> restartedB.tipHeight() == nodeA.tipHeight()
                && Arrays.equals(restartedB.stateRoot(), nodeA.stateRoot()));
        assertThat(currentEpoch(restartedB)).isEqualTo(1);

        Path snapshotDir = tempDir.resolve("post-activation-snapshot");
        long snapshotHeight = nodeA.snapshot(snapshotDir.toString());
        byte[] snapshotRoot = nodeA.stateRoot();
        stop("c");
        Path restoredLedgerBase = tempDir.resolve("ledger-c");
        deleteRecursively(restoredLedgerBase);
        copyRecursively(snapshotDir, restoredLedgerBase.resolve(CHAIN));
        AppChainSubsystem snapshotRestoredC = start(
                "c", KEY_C, members, pubA, ports, 2, true, membership);
        assertThat(snapshotRestoredC.tipHeight()).isEqualTo(snapshotHeight);
        assertThat(snapshotRestoredC.stateRoot()).containsExactly(snapshotRoot);
        assertThat(currentEpoch(snapshotRestoredC)).isEqualTo(1);
        await("snapshot-restored member agreement", () ->
                snapshotRestoredC.tipHeight() == nodeA.tipHeight()
                        && Arrays.equals(snapshotRestoredC.stateRoot(), nodeA.stateRoot()));

        stop("c");
        deleteRecursively(tempDir.resolve("ledger-c"));
        AppChainSubsystem lateJoinC = start(
                "c", KEY_C, members, pubA, ports, 2, true, membership);
        await("empty-ledger member catch-up", () -> lateJoinC.tipHeight() == nodeA.tipHeight()
                && Arrays.equals(lateJoinC.stateRoot(), nodeA.stateRoot()));
        assertThat(currentEpoch(lateJoinC)).isEqualTo(1);
        assertThat(activeDigest(lateJoinC))
                .isEqualTo(HexUtil.encodeHexString(NEXT_PROFILE.digest()));
    }

    private AppChainSubsystem start(String name,
                                    byte[] key,
                                    Set<String> members,
                                    String proposer,
                                    List<Integer> ports,
                                    int index,
                                    boolean fullCatalog,
                                    AppChainMembershipEpoch membership) throws Exception {
        List<AppChainConfig.AppPeer> peers = new ArrayList<>();
        for (int peer = 0; peer < ports.size(); peer++) {
            if (peer != index) peers.add(new AppChainConfig.AppPeer("localhost", ports.get(peer)));
        }
        AppChainConfig config = AppChainConfig.builder(CHAIN)
                .signingKeyHex(HexUtil.encodeHexString(key))
                .memberKeysHex(members)
                .peers(peers)
                .proposerKeyHex(proposer)
                .threshold(2)
                .blockIntervalMs(250)
                .stateMachineId("governed-e2e")
                .pluginSettings(SETTINGS)
                .build();
        CompositeStateMachine machine = machine(fullCatalog, membership);
        AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, machine,
                tempDir.resolve("ledger-" + name).toString(), null, log);
        nodes.put(name, subsystem);

        NodeServer server = new NodeServer(ports.get(index),
                N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC, false, 0, false),
                new MinimalChainState(), null, null, subsystem.serverAgentFactories());
        servers.put(name, server);
        Thread thread = new Thread(server::start, "governed-e2e-server-" + name);
        thread.setDaemon(true);
        thread.start();
        Thread.sleep(700);
        subsystem.start();
        return subsystem;
    }

    private CompositeStateMachine machine(boolean fullCatalog,
                                          AppChainMembershipEpoch membership) {
        AppChainConsensusProfile limits = AppChainTestProfiles.fromSettings(SETTINGS);
        RecordingComponent oldComponent = new RecordingComponent(OLD_DESCRIPTOR);
        List<CompositeProfileCatalog.Entry> entries = new ArrayList<>();
        entries.add(new CompositeProfileCatalog.Entry(
                OLD_PROFILE, List.of(oldComponent), List.of()));
        if (fullCatalog) {
            entries.add(new CompositeProfileCatalog.Entry(
                    NEXT_PROFILE,
                    List.of(new RecordingComponent(NEXT_DESCRIPTOR),
                            new RecordingComponent(AUDIT_DESCRIPTOR)),
                    List.of(new RecordingWorkflow(NEXT_WORKFLOW))));
        }
        CompositeProfileCatalog catalog = new CompositeProfileCatalog(entries,
                limits.effectsMaxPerBlock(), (int) limits.effectsResultWindowBlocks());
        AppStateMachineContext context = new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() { return SETTINGS; }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(limits);
            }
            @Override
            public Optional<com.bloxbean.cardano.yano.api.appchain.AppChainMembershipView>
            membershipView() {
                return Optional.of(ignored -> membership);
            }
        };
        return CompositeStateMachine.create("governed-e2e", context, catalog, OLD_PROFILE.digest());
    }

    private void stop(String name) throws InterruptedException {
        AppChainSubsystem node = nodes.remove(name);
        if (node != null) node.stop();
        NodeServer server = servers.remove(name);
        if (server != null) server.shutdown();
        Thread.sleep(700);
    }

    private static void finalizeCommand(AppChainSubsystem submitter,
                                        CompositeProfileGovernanceV1.Command command,
                                        AppChainSubsystem... expected) throws InterruptedException {
        String id = submitter.submitPrivilegedSystemMessage(
                CompositeProfileGovernanceV1.TOPIC, command.encode());
        byte[] messageId = HexUtil.decodeHexString(id);
        await("governance command " + command.getClass().getSimpleName(), () ->
                Arrays.stream(expected).allMatch(node ->
                        node.messageHeight(messageId).isPresent()));
        assertRoots(expected);
    }

    private static void finalizeBusiness(AppChainSubsystem submitter,
                                         String topic,
                                         AppChainSubsystem... expected) throws InterruptedException {
        byte[] messageId = HexUtil.decodeHexString(submitter.submit(topic, new byte[]{1}));
        await("business block", () -> Arrays.stream(expected).allMatch(node ->
                node.messageHeight(messageId).isPresent()));
        assertRoots(expected);
    }

    private static void assertRoots(AppChainSubsystem... nodes) {
        byte[] root = nodes[0].stateRoot();
        for (AppChainSubsystem node : nodes) {
            assertThat(node.stateRoot()).containsExactly(root);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> machineStatus(AppChainSubsystem node) {
        Object value = node.status().get("stateMachineStatus");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String proposalStatus(AppChainSubsystem node) {
        return String.valueOf(machineStatus(node).getOrDefault("proposalStatus", ""));
    }

    private static String activeDigest(AppChainSubsystem node) {
        return String.valueOf(machineStatus(node).getOrDefault("activeProfileDigest", ""));
    }

    private static long currentEpoch(AppChainSubsystem node) {
        Object value = machineStatus(node).get("currentEpoch");
        return value instanceof Number number ? number.longValue() : -1;
    }

    private static boolean connected(AppChainSubsystem node, int expected) {
        Object value = node.status().get("peers");
        return value instanceof Map<?, ?> peers && peers.size() == expected
                && peers.values().stream().allMatch(Boolean.TRUE::equals);
    }

    private static void await(String description, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static ComponentDescriptor descriptor(String version, String topic) {
        return descriptor("records", version, "records-state-v1", topic, 1);
    }

    private static ComponentDescriptor descriptor(String componentId,
                                                  String version,
                                                  String compatibilityId,
                                                  String topic,
                                                  int quota) {
        return new ComponentDescriptor(componentId, version, "config", compatibilityId,
                1, 0, List.of(topic), List.of("get"), quota);
    }

    private static final class RecordingComponent implements CompositeComponent {
        private final ComponentDescriptor descriptor;

        private RecordingComponent(ComponentDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override public ComponentDescriptor descriptor() { return descriptor; }

        @Override
        public void apply(AppBlock block, AppStateWriter state, AppEffectEmitter effects) {
            block.messages().forEach(message -> state.put(new byte[]{1}, message.getBody()));
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext state) {
            return state.get(new byte[]{1}).orElse(new byte[0]);
        }
    }

    private static final class RecordingWorkflow implements CompositeWorkflow {
        private final WorkflowDescriptor descriptor;

        private RecordingWorkflow(WorkflowDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override public WorkflowDescriptor descriptor() { return descriptor; }

        @Override
        public void apply(AppBlock block, CompositeWorkflowContext context) {
            block.messages().forEach(message -> descriptor.participants().forEach(
                    participant -> context.state(participant)
                            .put(new byte[]{2}, message.getBody())));
        }
    }

    private static byte[] seed(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String pubHex(byte[] privateKey) {
        return HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(privateKey));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var entries = Files.walk(source)) {
            for (Path entry : entries.toList()) {
                Path destination = target.resolve(source.relativize(entry));
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(entry, destination);
                }
            }
        }
    }

    private static final class MinimalChainState implements ChainState {
        @Override public void storeBlock(byte[] hash, Long number, Long slot, byte[] block) { }
        @Override public byte[] getBlock(byte[] hash) { return null; }
        @Override public boolean hasBlock(byte[] hash) { return false; }
        @Override public void storeBlockHeader(byte[] hash, Long number, Long slot, byte[] header) { }
        @Override public byte[] getBlockHeader(byte[] hash) { return null; }
        @Override public byte[] getBlockByNumber(Long number) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long number) { return null; }
        @Override public Point findNextBlock(Point current) { return null; }
        @Override public Point findNextBlockHeader(Point current) { return null; }
        @Override public List<Point> findBlocksInRange(Point from, Point to) {
            return Collections.emptyList();
        }
        @Override public Point findLastPointAfterNBlocks(Point from, long size) { return null; }
        @Override public boolean hasPoint(Point point) { return false; }
        @Override public Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long number) { return null; }
        @Override public void rollbackTo(Long slot) { }
        @Override public ChainTip getTip() { return null; }
        @Override public ChainTip getHeaderTip() { return null; }
    }
}
