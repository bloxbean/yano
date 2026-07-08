package com.bloxbean.cardano.yano.appchain.testkit;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;

/**
 * JUnit 5 lifecycle behind {@link AppChainCluster}: boots N in-process
 * app-chain nodes (subsystem + NodeServer each, full-mesh peers, temp
 * ledgers, generated keys), waits for connectivity, injects
 * {@link AppChainClusterHandle}, and tears everything down after the class.
 */
public class AppChainClusterExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(AppChainClusterExtension.class);
    private static final long MAGIC = 42;
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(AppChainClusterExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        AppChainCluster annotation = context.getRequiredTestClass().getAnnotation(AppChainCluster.class);
        if (annotation == null) {
            throw new ExtensionConfigurationException("@AppChainCluster annotation missing");
        }
        Cluster cluster = Cluster.start(annotation);
        context.getStore(NAMESPACE).put("cluster", cluster);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Cluster cluster = context.getStore(NAMESPACE).get("cluster", Cluster.class);
        if (cluster != null) {
            cluster.stop();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == AppChainClusterHandle.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE).get("cluster", Cluster.class).handle;
    }

    // ------------------------------------------------------------------

    private static final class Cluster {
        final List<AppChainSubsystem> subsystems = new ArrayList<>();
        final List<NodeServer> servers = new ArrayList<>();
        AppChainClusterHandle handle;
        Path tempDir;

        static Cluster start(AppChainCluster annotation) throws Exception {
            int nodeCount = Math.max(1, annotation.nodes());
            Cluster cluster = new Cluster();
            cluster.tempDir = Files.createTempDirectory("appchain-testkit-");

            // Generate member identities
            SecureRandom random = new SecureRandom();
            List<byte[]> seeds = new ArrayList<>();
            List<String> publicKeys = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                byte[] seed = new byte[32];
                random.nextBytes(seed);
                seeds.add(seed);
                publicKeys.add(HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(seed)));
            }
            Set<String> members = Set.copyOf(publicKeys);
            String proposer = publicKeys.get(0);
            int threshold = annotation.threshold() > 0 ? annotation.threshold() : nodeCount;

            // Allocate a server port per node; full-mesh peers
            List<Integer> ports = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                ports.add(freePort());
            }

            for (int i = 0; i < nodeCount; i++) {
                List<AppChainConfig.AppPeer> peers = new ArrayList<>();
                for (int j = 0; j < nodeCount; j++) {
                    if (j != i) {
                        peers.add(new AppChainConfig.AppPeer("localhost", ports.get(j)));
                    }
                }
                AppChainConfig config = AppChainConfig.builder(annotation.chainId())
                        .signingKeyHex(HexUtil.encodeHexString(seeds.get(i)))
                        .memberKeysHex(members)
                        .peers(peers)
                        .proposerKeyHex(proposer)
                        .threshold(threshold)
                        .blockIntervalMs(annotation.blockIntervalMs())
                        .stateMachineId(annotation.stateMachine())
                        .build();
                AppChainSubsystem subsystem = new AppChainSubsystem(config, MAGIC, null, null,
                        cluster.tempDir.resolve("ledger-" + i).toString(), null, log);
                cluster.subsystems.add(subsystem);

                NodeServer server = new NodeServer(ports.get(i),
                        N2NVersionTableConstant.v11AndAboveWithAppLayer(MAGIC, false, 0, false),
                        new MinimalChainState(),
                        null, null,
                        subsystem.serverAgentFactories());
                cluster.servers.add(server);
                Thread thread = new Thread(server::start);
                thread.setDaemon(true);
                thread.start();
            }
            Thread.sleep(800); // let servers bind

            List<AppChainGateway> gateways = new ArrayList<>(cluster.subsystems);
            for (AppChainSubsystem subsystem : cluster.subsystems) {
                subsystem.start();
            }
            cluster.handle = new AppChainClusterHandle(gateways, publicKeys);

            // Wait until every node is connected to all its peers
            cluster.handle.await("cluster connectivity", 30_000, () ->
                    gateways.stream().allMatch(g -> {
                        Object peers = g.status().get("peers");
                        return peers instanceof Map<?, ?> peerMap
                                && peerMap.size() == nodeCount - 1
                                && peerMap.values().stream().allMatch(Boolean.TRUE::equals);
                    }));
            log.info("App-chain testkit cluster up: {} node(s), chain '{}'",
                    nodeCount, annotation.chainId());
            return cluster;
        }

        void stop() {
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

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }
    }

    /** Minimal ChainState — the testkit exercises the app layer only. */
    private static final class MinimalChainState implements ChainState {
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
