package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.Agent;
import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgInitAck;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessages;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Outbound connection to one app-group peer, carrying the app message
 * submission protocol (100) plus keep-alive on a standard N2N handshake.
 * <p>
 * M1 note: this is a dedicated connection owned by the app-chain subsystem.
 * Unifying app diffusion with the L1 sync session (single connection per peer
 * pair) is planned once the upstream-selection work stabilizes — the handshake
 * and mux already support it.
 */
final class AppPeerClient implements AppPeerLink {
    private static final int REPLAY_QUEUE_LIMIT = 200;
    private static final Runnable NOOP = () -> { };

    /** Callback for catch-up replies fetched over protocol 103. */
    interface CatchUpHandler {
        void onBlocks(String peerId, java.util.List<byte[]> blocks, long serverTipHeight);
    }

    /** Small transport boundary used to make publication/start races deterministic in tests. */
    interface PeerTransport {
        void start();

        boolean isRunning();

        void shutdown();
    }

    @FunctionalInterface
    interface PeerTransportFactory {
        PeerTransport create(AppChainConfig.AppPeer peer,
                             HandshakeAgent handshakeAgent,
                             AppMsgSubmissionAgent appMsgAgent,
                             KeepAliveAgent keepAliveAgent,
                             AppChainSyncClientAgent syncAgent);
    }

    private final AppChainConfig.AppPeer peer;
    private final long protocolMagic;
    private final AppMsgSubmissionConfig appMsgConfig;
    private final CatchUpHandler catchUpHandler;
    private final Logger log;
    private final PeerTransportFactory transportFactory;
    private final Runnable beforeTransportStart;
    private final Runnable beforeDelegateStart;

    /**
     * Bounded recent-message cache. Entries remain here after being offered so
     * an agent reset during TCP reconnect cannot lose an unacknowledged body.
     */
    private final Deque<AppMessage> replayQueue = new ArrayDeque<>();

    private volatile PeerTransport client;
    private volatile AppMsgSubmissionAgent agent;
    /**
     * The agent whose protocol-100 negotiation and replay hand-off have
     * completed. Keeping the identity (rather than a boolean) prevents a late
     * callback from an old client from making its replacement appear ready.
     */
    private volatile AppMsgSubmissionAgent readyAgent;
    private volatile KeepAliveAgent keepAliveAgent;
    private volatile com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent syncAgent;
    private volatile boolean shutdown;
    /** Guarded by {@link #replayQueue}; distinguishes internal reconnects that reuse an agent. */
    private long connectionEpoch;
    /** Epoch whose protocol-100 MsgInitAck is currently expected. Guarded by replayQueue. */
    private long awaitingInitAckEpoch = -1;

    AppPeerClient(AppChainConfig.AppPeer peer,
                  long protocolMagic,
                  AppMsgSubmissionConfig appMsgConfig,
                  Logger log) {
        this(peer, protocolMagic, appMsgConfig, null, log,
                AppPeerClient::createTcpTransport, NOOP, NOOP);
    }

    AppPeerClient(AppChainConfig.AppPeer peer,
                  long protocolMagic,
                  AppMsgSubmissionConfig appMsgConfig,
                  CatchUpHandler catchUpHandler,
                  Logger log) {
        this(peer, protocolMagic, appMsgConfig, catchUpHandler, log,
                AppPeerClient::createTcpTransport, NOOP, NOOP);
    }

    AppPeerClient(AppChainConfig.AppPeer peer,
                  long protocolMagic,
                  AppMsgSubmissionConfig appMsgConfig,
                  CatchUpHandler catchUpHandler,
                  Logger log,
                  PeerTransportFactory transportFactory,
                  Runnable beforeTransportStart,
                  Runnable beforeDelegateStart) {
        this.peer = Objects.requireNonNull(peer, "peer");
        this.protocolMagic = protocolMagic;
        this.appMsgConfig = Objects.requireNonNull(appMsgConfig, "appMsgConfig");
        this.catchUpHandler = catchUpHandler;
        this.log = Objects.requireNonNull(log, "log");
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        this.beforeTransportStart = Objects.requireNonNull(beforeTransportStart, "beforeTransportStart");
        this.beforeDelegateStart = Objects.requireNonNull(beforeDelegateStart, "beforeDelegateStart");
    }

    /**
     * Request finalized app blocks [from..to] over protocol 103.
     * @return false when disconnected or a request is already in flight
     */
    @Override
    public boolean requestCatchUp(String chainId, long fromHeight, long toHeight) {
        var currentSyncAgent = syncAgent;
        return currentSyncAgent != null && isConnected()
                && currentSyncAgent.requestRange(chainId, fromHeight, toHeight);
    }

    @Override
    public String peerId() {
        return peer.toString();
    }

    @Override
    public String transport() {
        return "dedicated";
    }

    @Override
    public boolean isConnected() {
        PeerTransport c = client;
        AppMsgSubmissionAgent currentAgent = agent;
        return c != null && c.isRunning()
                && currentAgent != null && readyAgent == currentAgent;
    }

    /** A running transport may still be negotiating its app protocols. */
    private boolean isTransportRunning() {
        PeerTransport c = client;
        return c != null && c.isRunning();
    }

    /**
     * Queue a message for diffusion to this peer. Deliberately NOT on the
     * object monitor: {@link #ensureConnected()} holds that through a blocking
     * connect attempt (with retry sleeps), and a dead peer must never stall
     * submitters/relayers (ADR 008.2 delivery note — found by the rotation
     * partial-round test).
     */
    @Override
    public void enqueue(AppMessage message) {
        Objects.requireNonNull(message, "message");
        if (shutdown)
            return;
        synchronized (replayQueue) {
            // Close the admission race with shutdown(), whose final queue
            // clear uses this same monitor.
            if (shutdown) {
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            replayQueue.removeIf(queued -> queued.isExpired(now));
            if (message.isExpired(now)
                    || message.getSize() > appMsgConfig.getMaxMessageSize()
                    || (!appMsgConfig.getChainIds().isEmpty()
                    && !appMsgConfig.getChainIds().contains(message.getChainId()))) {
                return;
            }
            if (replayQueue.contains(message)) {
                return;
            }
            if (replayQueue.size() >= REPLAY_QUEUE_LIMIT) {
                replayQueue.pollFirst();
            }
            replayQueue.addLast(message);

            AppMsgSubmissionAgent currentAgent = agent;
            PeerTransport currentClient = client;
            if (currentAgent != null && readyAgent == currentAgent
                    && currentClient != null && currentClient.isRunning()) {
                currentAgent.enqueueMessage(message);
            }
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean connecting =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Non-blocking connect: the underlying client start RETRIES an unreachable
     * peer in a sleep loop, which must never run on the shared subsystem
     * scheduler — with one dead peer it wedged proposer/anchor/catch-up ticks
     * entirely (found by the 008.2 partial-round test). Attempts run on their
     * own daemon thread, one at a time.
     */
    @Override
    public void ensureConnectedAsync() {
        if (shutdown || isTransportRunning() || !connecting.compareAndSet(false, true)) {
            return;
        }
        Thread connector = new Thread(() -> {
            try {
                ensureConnected();
            } finally {
                connecting.set(false);
            }
        }, "app-peer-connect-" + peer);
        connector.setDaemon(true);
        connector.start();
    }

    /** Connect if not connected; blocking — use {@link #ensureConnectedAsync()}. */
    synchronized void ensureConnected() {
        if (shutdown || isTransportRunning())
            return;

        disposeClient();

        AppMsgSubmissionAgent newAgent = new AppMsgSubmissionAgent(appMsgConfig);
        newAgent.addListener(new AppMsgSubmissionListener() {
            @Override
            public void onDisconnect() {
                synchronized (replayQueue) {
                    if (agent == newAgent) {
                        readyAgent = null;
                        awaitingInitAckEpoch = -1;
                        connectionEpoch++;
                    }
                }
            }

            @Override
            public void handleInitAck(MsgInitAck ack) {
                // MsgInitAck, rather than the outer N2N handshake, is the
                // readiness boundary for protocol 100.  The replay offer and
                // publication are one atomic hand-off with enqueue(): a
                // submitter either joins the replay queue or uses this fully
                // negotiated agent, never a transport that is merely retrying
                // or an app protocol still in Init/InitAck.
                synchronized (replayQueue) {
                    if (shutdown || agent != newAgent
                            || awaitingInitAckEpoch != connectionEpoch) {
                        return;
                    }
                    offerReplayQueueLocked(newAgent);
                    readyAgent = newAgent;
                    awaitingInitAckEpoch = -1;
                }
            }

            @Override
            public void handleRequestMessageIds(MsgRequestMessageIds request) {
                newAgent.sendNextMessage();
            }

            @Override
            public void handleRequestMessages(MsgRequestMessages request) {
                newAgent.sendNextMessage();
            }
        });

        KeepAliveAgent newKeepAlive = new KeepAliveAgent(true);

        com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent newSyncAgent = null;
        if (catchUpHandler != null) {
            var createdSyncAgent = new com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent();
            createdSyncAgent.addListener(
                    new com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncListener() {
                        @Override
                        public void blocksReceived(java.util.List<byte[]> blocks, long serverTipHeight) {
                            catchUpHandler.onBlocks(peerId(), blocks, serverTipHeight);
                        }
                    });
            newSyncAgent = createdSyncAgent;
        }

        HandshakeAgent handshakeAgent = new HandshakeAgent(
                N2NVersionTableConstant.v11AndAboveWithAppLayer(protocolMagic, false, 0, false), true);
        handshakeAgent.addListener(new HandshakeAgentListener() {
            @Override
            public void handshakeOk() {
                log.info("App-peer handshake OK: {} — activating app message protocol", peer);
                synchronized (replayQueue) {
                    if (shutdown || agent != newAgent) {
                        return;
                    }
                    awaitingInitAckEpoch = connectionEpoch;
                }
                newAgent.sendNextMessage(); // MsgInit with our chain-ids
            }

            @Override
            public void handshakeError(Reason reason) {
                log.warn("App-peer handshake failed for {}: {}", peer, reason);
            }
        });

        PeerTransport newClient = new TerminalPeerTransport(
                Objects.requireNonNull(
                        transportFactory.create(peer, handshakeAgent, newAgent, newKeepAlive, newSyncAgent),
                        "transportFactory result"),
                beforeDelegateStart);
        boolean published = false;
        try {
            // Publish the complete transport tuple BEFORE start().  start()
            // retries an unreachable peer indefinitely, so shutdown() must be
            // able to reach the in-progress client.  Publish under the same
            // lock as queue hand-off/disposal so enqueue never observes a
            // partially installed connection tuple.
            boolean aborted;
            synchronized (replayQueue) {
                aborted = shutdown;
                if (!aborted) {
                    this.client = newClient;
                    this.agent = newAgent;
                    this.readyAgent = null;
                    this.keepAliveAgent = newKeepAlive;
                    this.syncAgent = newSyncAgent;
                    this.connectionEpoch++;
                    this.awaitingInitAckEpoch = -1;
                    published = true;
                }
            }
            if (aborted) {
                shutdownClient(newClient);
                return;
            }

            synchronized (replayQueue) {
                if (shutdown || client != newClient || agent != newAgent) {
                    return;
                }
            }

            // Deliberate deterministic seam in the final publication-to-start
            // gap. TerminalPeerTransport makes this late start a no-op if
            // shutdown wins while the hook is running.
            beforeTransportStart.run();
            newClient.start();
            if (shutdown) {
                if (clearConnectionIfCurrent(newAgent)) {
                    shutdownClient(newClient);
                }
                return;
            }
            log.info("App-peer connection started: {}", peer);
        } catch (Throwable startFailure) {
            boolean cleanupOwnedHere = !published || clearConnectionIfCurrent(newAgent);
            Throwable cleanupFailure = null;
            if (cleanupOwnedHere) {
                try {
                    shutdownClient(newClient);
                } catch (Throwable failure) {
                    cleanupFailure = failure;
                }
            }

            Throwable failure = cleanupFailure == null
                    ? startFailure
                    : LifecycleFailures.merge(startFailure, cleanupFailure);
            if (cleanupFailure != null || startFailure instanceof Error) {
                throwUnchecked(failure);
            }
            log.warn("App-peer connection to {} failed: {}", peer, startFailure.toString());
        }
    }

    /** Caller owns {@link #replayQueue}'s monitor. */
    private void offerReplayQueueLocked(AppMsgSubmissionAgent target) {
        long now = System.currentTimeMillis() / 1000;
        replayQueue.removeIf(queued -> queued.isExpired(now));
        for (AppMessage queued : replayQueue) {
            target.enqueueMessage(queued);
        }
    }

    /** Periodic keep-alive heartbeat; no-op when disconnected. */
    @Override
    public void keepAliveTick() {
        KeepAliveAgent ka = keepAliveAgent;
        if (ka != null && isConnected()) {
            try {
                ka.sendNextMessage();
            } catch (Exception e) {
                log.debug("Keep-alive to app peer {} failed: {}", peer, e.toString());
            }
        }
    }

    /**
     * Deliberately NOT on the object monitor: a connector thread can hold that
     * monitor indefinitely while retrying a dead peer inside
     * {@link #ensureConnected()} — shutdown must still proceed (it closes the
     * published in-progress client, which breaks the retry loop).
     */
    @Override
    public void shutdown() {
        shutdown = true;
        Throwable failure = null;
        try {
            disposeClient();
        } catch (Throwable shutdownFailure) {
            failure = shutdownFailure;
        } finally {
            synchronized (replayQueue) {
                replayQueue.clear();
            }
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
    }

    private void disposeClient() {
        PeerTransport c;
        synchronized (replayQueue) {
            c = client;
            client = null;
            agent = null;
            readyAgent = null;
            keepAliveAgent = null;
            syncAgent = null;
            awaitingInitAckEpoch = -1;
            connectionEpoch++;
        }
        shutdownClient(c);
    }

    private boolean clearConnectionIfCurrent(AppMsgSubmissionAgent expectedAgent) {
        synchronized (replayQueue) {
            if (agent == expectedAgent) {
                client = null;
                agent = null;
                readyAgent = null;
                keepAliveAgent = null;
                syncAgent = null;
                awaitingInitAckEpoch = -1;
                connectionEpoch++;
                return true;
            }
            return false;
        }
    }

    private void shutdownClient(PeerTransport target) {
        if (target != null) {
            target.shutdown();
        }
    }

    private static PeerTransport createTcpTransport(AppChainConfig.AppPeer peer,
                                                    HandshakeAgent handshakeAgent,
                                                    AppMsgSubmissionAgent appMsgAgent,
                                                    KeepAliveAgent keepAliveAgent,
                                                    AppChainSyncClientAgent syncAgent) {
        Agent[] agents = syncAgent == null
                ? new Agent[] {appMsgAgent, keepAliveAgent}
                : new Agent[] {appMsgAgent, keepAliveAgent, syncAgent};
        return new YaciPeerTransport(new TCPNodeClient(
                peer.host(), peer.port(), handshakeAgent, agents));
    }

    /** Thin adapter; terminal lifecycle semantics are supplied by {@link TerminalPeerTransport}. */
    private record YaciPeerTransport(TCPNodeClient delegate) implements PeerTransport {
        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }

    /**
     * Gives an otherwise restartable transport a terminal shutdown state.
     * In particular, shutdown-before-start cannot be undone by a late caller,
     * and shutdown racing an in-progress start interrupts that blocking start
     * before performing a final cleanup pass.
     */
    private static final class TerminalPeerTransport implements PeerTransport {
        private final PeerTransport delegate;
        private final Runnable beforeDelegateStart;
        private final Object lifecycleMonitor = new Object();

        private boolean terminal;
        private Thread starter;
        private Thread cleanupOwner;
        private boolean cleanupFinished;
        private Throwable terminalCleanupFailure;

        private TerminalPeerTransport(PeerTransport delegate, Runnable beforeDelegateStart) {
            this.delegate = delegate;
            this.beforeDelegateStart = beforeDelegateStart;
        }

        @Override
        public void start() {
            synchronized (lifecycleMonitor) {
                if (terminal) {
                    return;
                }
                starter = Thread.currentThread();
            }

            Throwable failure = null;
            try {
                boolean startAllowed;
                synchronized (lifecycleMonitor) {
                    startAllowed = !terminal;
                }
                // Deterministic seam for the final cancellation race: a
                // shutdown here interrupts this registered owner. The Yaci
                // Session.start path observes interruption in connect/sleep,
                // and this owner's finally is the sole delegate cleaner.
                beforeDelegateStart.run();
                if (startAllowed) {
                    delegate.start();
                }
            } catch (Throwable startFailure) {
                failure = startFailure;
            } finally {
                boolean ownsCleanup;
                synchronized (lifecycleMonitor) {
                    ownsCleanup = cleanupOwner == Thread.currentThread() && !cleanupFinished;
                }
                Throwable cleanupFailure = null;
                if (ownsCleanup) {
                    try {
                        delegate.shutdown();
                    } catch (Throwable caught) {
                        cleanupFailure = caught;
                    }
                }
                synchronized (lifecycleMonitor) {
                    if (ownsCleanup) {
                        terminalCleanupFailure = cleanupFailure;
                        cleanupFinished = true;
                    }
                    starter = null;
                    lifecycleMonitor.notifyAll();
                }
                if (cleanupFailure != null) {
                    failure = LifecycleFailures.merge(failure, cleanupFailure);
                }
            }
            if (failure != null) {
                throwUnchecked(failure);
            }
        }

        @Override
        public boolean isRunning() {
            synchronized (lifecycleMonitor) {
                return !terminal && delegate.isRunning();
            }
        }

        @Override
        public void shutdown() {
            Thread startThread;
            boolean cleanupHere;
            synchronized (lifecycleMonitor) {
                terminal = true;
                startThread = starter;
                if (cleanupOwner == null) {
                    cleanupOwner = startThread != null ? startThread : Thread.currentThread();
                }
                cleanupHere = cleanupOwner == Thread.currentThread()
                        && startThread == null && !cleanupFinished;
            }
            if (startThread != null && startThread != Thread.currentThread()) {
                startThread.interrupt();
            } else if (startThread == Thread.currentThread()) {
                Thread.currentThread().interrupt();
            }

            if (cleanupHere) {
                Throwable cleanupFailure = null;
                try {
                    delegate.shutdown();
                } catch (Throwable failure) {
                    cleanupFailure = failure;
                }
                synchronized (lifecycleMonitor) {
                    terminalCleanupFailure = cleanupFailure;
                    cleanupFinished = true;
                    lifecycleMonitor.notifyAll();
                }
                if (cleanupFailure != null) {
                    throwUnchecked(cleanupFailure);
                }
                return;
            }

            // A re-entrant shutdown from the starter cannot wait for its own
            // finally; that finally remains responsible for cleanup.
            if (startThread == Thread.currentThread()) {
                return;
            }

            boolean interrupted = false;
            Throwable cleanupFailure;
            synchronized (lifecycleMonitor) {
                while (!cleanupFinished) {
                    try {
                        lifecycleMonitor.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                cleanupFailure = terminalCleanupFailure;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (cleanupFailure != null) {
                throwUnchecked(cleanupFailure);
            }
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }
}
