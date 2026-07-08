package com.bloxbean.cardano.yano.runtime.server;

import com.bloxbean.cardano.yaci.core.network.server.AgentFactory;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.VersionTable;
import com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingServerAgent;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionConfig;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.runtime.handlers.YaciTxSubmissionHandler;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yano.p2p.connection.RelayConnectionInfo;
import com.bloxbean.cardano.yano.p2p.connection.RelayConnectionManager;
import com.bloxbean.cardano.yano.p2p.governor.PeerStoreEntry;
import com.bloxbean.cardano.yano.p2p.peersharing.RelayPeerSharingProvider;
import com.bloxbean.cardano.yano.runtime.tx.TransactionAdmission;
import com.bloxbean.cardano.yano.p2p.tx.diffusion.TxDiffusion;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns the N2N server lifecycle and transaction-submission handler.
 */
public final class ServeSubsystem implements Subsystem {
    private final int serverPort;
    private final long protocolMagic;
    private final ChainState chainState;
    private final TransactionAdmission transactionAdmission;
    private final boolean blockProducerMode;
    private final Supplier<TxDiffusion> txDiffusionSupplier;
    private final boolean relayAutoDiscovery;
    private final String advertisedHost;
    private final int advertisedPort;
    private final boolean allowPrivateRelayAddresses;
    private final Supplier<List<PeerStoreEntry>> peerStoreSupplier;
    private final RelayConnectionManager relayConnectionManager;
    private final Logger log;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile NodeServer nodeServer;
    private volatile YaciTxSubmissionHandler txSubmissionHandler;
    private volatile Thread serverThread;
    private volatile List<AgentFactory> appLayerAgentFactories = List.of();

    public ServeSubsystem(int serverPort,
                          long protocolMagic,
                          ChainState chainState,
                          TransactionAdmission transactionAdmission,
                          boolean blockProducerMode,
                          Logger log) {
        this(serverPort, protocolMagic, chainState, transactionAdmission, blockProducerMode, null, log);
    }

    public ServeSubsystem(int serverPort,
                          long protocolMagic,
                          ChainState chainState,
                          TransactionAdmission transactionAdmission,
                          boolean blockProducerMode,
                          Supplier<TxDiffusion> txDiffusionSupplier,
                          Logger log) {
        this(serverPort, protocolMagic, chainState, transactionAdmission, blockProducerMode,
                txDiffusionSupplier, false, null, 0, false, null, log);
    }

    public ServeSubsystem(int serverPort,
                          long protocolMagic,
                          ChainState chainState,
                          TransactionAdmission transactionAdmission,
                          boolean blockProducerMode,
                          Supplier<TxDiffusion> txDiffusionSupplier,
                          boolean relayAutoDiscovery,
                          String advertisedHost,
                          int advertisedPort,
                          boolean allowPrivateRelayAddresses,
                          Supplier<List<PeerStoreEntry>> peerStoreSupplier,
                          Logger log) {
        this(serverPort, protocolMagic, chainState, transactionAdmission, blockProducerMode,
                txDiffusionSupplier, relayAutoDiscovery, advertisedHost, advertisedPort,
                allowPrivateRelayAddresses, peerStoreSupplier, null, log);
    }

    public ServeSubsystem(int serverPort,
                          long protocolMagic,
                          ChainState chainState,
                          TransactionAdmission transactionAdmission,
                          boolean blockProducerMode,
                          Supplier<TxDiffusion> txDiffusionSupplier,
                          boolean relayAutoDiscovery,
                          String advertisedHost,
                          int advertisedPort,
                          boolean allowPrivateRelayAddresses,
                          Supplier<List<PeerStoreEntry>> peerStoreSupplier,
                          RelayConnectionManager relayConnectionManager,
                          Logger log) {
        this.serverPort = serverPort;
        this.protocolMagic = protocolMagic;
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.transactionAdmission = Objects.requireNonNull(transactionAdmission, "transactionAdmission");
        this.blockProducerMode = blockProducerMode;
        this.txDiffusionSupplier = txDiffusionSupplier;
        this.relayAutoDiscovery = relayAutoDiscovery;
        this.advertisedHost = advertisedHost;
        this.advertisedPort = advertisedPort > 0 ? advertisedPort : serverPort;
        this.allowPrivateRelayAddresses = allowPrivateRelayAddresses;
        this.peerStoreSupplier = peerStoreSupplier != null ? peerStoreSupplier : List::of;
        this.relayConnectionManager = relayConnectionManager;
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public String name() {
        return "serve";
    }

    /**
     * Install app-layer (protocol 100+) server agent factories. When set, the
     * server also advertises the app-layer handshake version (V100) so app-chain
     * peers can negotiate it; plain Cardano peers are unaffected. Must be called
     * before {@link #start()}.
     */
    public void enableAppLayer(List<AgentFactory> agentFactories) {
        this.appLayerAgentFactories = agentFactories != null ? List.copyOf(agentFactories) : List.of();
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        Thread existingThread = serverThread;
        if (existingThread != null && existingThread.isAlive()) {
            throw new IllegalStateException("NodeServer is still stopping");
        }

        try {
            log.info("Starting NodeServer on port {}...", serverPort);
            log.info("Protocol magic: {}", protocolMagic);
            logStartTip();

            TxDiffusion txDiffusion = txDiffusionSupplier != null ? txDiffusionSupplier.get() : null;
            txSubmissionHandler = new YaciTxSubmissionHandler(transactionAdmission, blockProducerMode, txDiffusion);
            TxSubmissionConfig txSubmissionConfig = TxSubmissionConfig.builder()
                    .batchSize(10)
                    .useBlockingMode(true)
                    .build();

            RelayPeerSharingProvider peerSharingProvider = new RelayPeerSharingProvider(
                    relayAutoDiscovery,
                    advertisedHost,
                    advertisedPort,
                    allowPrivateRelayAddresses,
                    peerStoreSupplier,
                    relayConnectionManager != null
                            ? () -> relayConnectionManager.snapshot().connections()
                            : List::of,
                    true,
                    log);
            int peerSharing = relayAutoDiscovery ? 1 : 0;
            List<AgentFactory> appFactories = appLayerAgentFactories;
            VersionTable versionTable = appFactories.isEmpty()
                    ? N2NVersionTableConstant.v11AndAbove(protocolMagic, false, peerSharing, false)
                    : N2NVersionTableConstant.v11AndAboveWithAppLayer(protocolMagic, false, peerSharing, false);
            List<AgentFactory> agentFactories = new ArrayList<>();
            if (relayAutoDiscovery) {
                agentFactories.add(() -> new PeerSharingServerAgent(peerSharingProvider::peers));
            }
            agentFactories.addAll(appFactories);
            if (!appFactories.isEmpty()) {
                log.info("App-layer server agents enabled ({} factory/factories)", appFactories.size());
            }
            NodeServer server = new NodeServer(serverPort,
                    versionTable,
                    chainState,
                    txSubmissionHandler,
                    txSubmissionConfig,
                    agentFactories);
            if (relayConnectionManager != null) {
                server.setConnectionListener(relayConnectionManager.yaciServerConnectionListener());
            }
            nodeServer = server;

            Thread thread = new Thread(() -> {
                try {
                    server.start();
                } catch (Exception e) {
                    if (running.get()) {
                        log.error("NodeServer failed", e);
                    } else {
                        log.debug("NodeServer stopped", e);
                    }
                } finally {
                    running.set(false);
                    if (nodeServer == server) {
                        nodeServer = null;
                    }
                    if (serverThread == Thread.currentThread()) {
                        serverThread = null;
                    }
                }
            });
            thread.setDaemon(false);
            thread.setName("YanoServer");
            serverThread = thread;
            running.set(true);
            thread.start();

            Thread.sleep(2000);
            if (!running.get()) {
                nodeServer = null;
                serverThread = null;
                throw new IllegalStateException("NodeServer failed during startup");
            }

            log.info("NodeServer started successfully on port {}", serverPort);
            log.info("Server is ready to accept connections from Cardano nodes");
            if (relayAutoDiscovery) {
                log.info("Relay peer-sharing enabled (advertised endpoint: {}:{})",
                        advertisedHost != null && !advertisedHost.isBlank() ? advertisedHost : "<none>",
                        advertisedPort);
            }
        } catch (Exception e) {
            running.set(false);
            nodeServer = null;
            serverThread = null;
            log.error("Failed to start NodeServer", e);
            throw new RuntimeException("Failed to start server", e);
        }
    }

    @Override
    public synchronized void stop() {
        stopAndAwait(Duration.ofSeconds(10));
    }

    public synchronized boolean stopAndAwait(Duration timeout) {
        boolean wasRunning = running.get();
        NodeServer server = nodeServer;
        Thread thread = serverThread;
        if (!wasRunning && server == null && thread == null) {
            return true;
        }
        running.set(false);
        if (server != null) {
            try {
                server.shutdown();
            } catch (Exception e) {
                log.warn("Error stopping NodeServer", e);
            }
        }
        boolean stopped = awaitServerThreadExit(thread, timeout);
        if (wasRunning && stopped) {
            if (nodeServer == server) {
                nodeServer = null;
            }
            if (serverThread == thread) {
                serverThread = null;
            }
            log.info("NodeServer stopped");
        } else if (wasRunning) {
            log.warn("NodeServer stop requested but server thread did not exit within timeout");
        } else if (stopped) {
            if (nodeServer == server) {
                nodeServer = null;
            }
            if (serverThread == thread) {
                serverThread = null;
            }
        }
        return stopped;
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isRelayAutoDiscoveryEnabled() {
        return relayAutoDiscovery;
    }

    public String advertisedHost() {
        return advertisedHost;
    }

    public int advertisedPort() {
        return advertisedPort;
    }

    public NodeServer server() {
        return running.get() ? nodeServer : null;
    }

    public boolean notifyNewDataAvailable() {
        NodeServer server = server();
        if (server == null) {
            return false;
        }
        try {
            server.notifyNewDataAvailable();
            return true;
        } catch (Exception e) {
            log.warn("Error notifying server agents", e);
            return false;
        }
    }

    private boolean awaitServerThreadExit(Thread thread, Duration timeout) {
        if (thread == null || thread == Thread.currentThread()) {
            return true;
        }
        try {
            Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(10);
            thread.join(Math.max(1L, effectiveTimeout.toMillis()));
            if (thread.isAlive()) {
                log.warn("NodeServer thread did not stop within {}ms", effectiveTimeout.toMillis());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for NodeServer thread to stop", e);
            return false;
        }
    }

    @Override
    public SubsystemHealth health() {
        return running.get()
                ? SubsystemHealth.up(name())
                : SubsystemHealth.down(name(), "stopped");
    }

    private void logStartTip() {
        ChainTip tip = chainState.getTip();
        if (tip != null) {
            log.info("Server starting with tip: slot={}, blockNumber={}, hash={}",
                    tip.getSlot(), tip.getBlockNumber(), HexUtil.encodeHexString(tip.getBlockHash()));
            try {
                Point firstBlock = chainState.getFirstBlock();
                log.info("First block available: {}", firstBlock);
            } catch (Exception e) {
                log.warn("Error checking first block", e);
            }
        } else if (blockProducerMode) {
            log.info("Server starting with empty chain state - block producer will create genesis block");
        } else {
            log.error("CRITICAL: Server starting with empty chain state (no tip)");
            log.error("Real Cardano nodes will not connect to an empty server");
            log.error("Yano must sync some blockchain data first before serving");
        }
    }
}
