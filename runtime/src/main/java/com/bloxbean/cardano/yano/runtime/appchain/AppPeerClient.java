package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.network.TCPNodeClient;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionListener;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessageIds;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.messages.MsgRequestMessages;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgent;
import com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.Reason;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.keepalive.KeepAliveAgent;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
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
final class AppPeerClient {
    private static final int REPLAY_QUEUE_LIMIT = 200;

    /** Callback for catch-up replies fetched over protocol 103. */
    interface CatchUpHandler {
        void onBlocks(String peerId, java.util.List<byte[]> blocks, long serverTipHeight);
    }

    private final AppChainConfig.AppPeer peer;
    private final long protocolMagic;
    private final AppMsgSubmissionConfig appMsgConfig;
    private final CatchUpHandler catchUpHandler;
    private final Logger log;

    /** Messages to re-offer after a reconnect (agents are recreated per connection). */
    private final Deque<AppMessage> replayQueue = new ArrayDeque<>();

    private volatile TCPNodeClient client;
    private volatile AppMsgSubmissionAgent agent;
    private volatile KeepAliveAgent keepAliveAgent;
    private volatile com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent syncAgent;
    private volatile boolean shutdown;

    AppPeerClient(AppChainConfig.AppPeer peer,
                  long protocolMagic,
                  AppMsgSubmissionConfig appMsgConfig,
                  Logger log) {
        this(peer, protocolMagic, appMsgConfig, null, log);
    }

    AppPeerClient(AppChainConfig.AppPeer peer,
                  long protocolMagic,
                  AppMsgSubmissionConfig appMsgConfig,
                  CatchUpHandler catchUpHandler,
                  Logger log) {
        this.peer = Objects.requireNonNull(peer, "peer");
        this.protocolMagic = protocolMagic;
        this.appMsgConfig = Objects.requireNonNull(appMsgConfig, "appMsgConfig");
        this.catchUpHandler = catchUpHandler;
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Request finalized app blocks [from..to] over protocol 103.
     * @return false when disconnected or a request is already in flight
     */
    boolean requestCatchUp(String chainId, long fromHeight, long toHeight) {
        var currentSyncAgent = syncAgent;
        return currentSyncAgent != null && isConnected()
                && currentSyncAgent.requestRange(chainId, fromHeight, toHeight);
    }

    String peerId() {
        return peer.toString();
    }

    boolean isConnected() {
        TCPNodeClient c = client;
        return c != null && c.isRunning();
    }

    /** Queue a message for diffusion to this peer. */
    synchronized void enqueue(AppMessage message) {
        if (shutdown)
            return;
        AppMsgSubmissionAgent a = agent;
        if (a != null && isConnected()) {
            a.enqueueMessage(message);
        } else {
            if (replayQueue.size() >= REPLAY_QUEUE_LIMIT) {
                replayQueue.pollFirst();
            }
            replayQueue.addLast(message);
        }
    }

    /** Connect if not connected; called periodically by the subsystem. */
    synchronized void ensureConnected() {
        if (shutdown || isConnected())
            return;

        disposeClient();

        AppMsgSubmissionAgent newAgent = new AppMsgSubmissionAgent(appMsgConfig);
        newAgent.addListener(new AppMsgSubmissionListener() {
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
                newAgent.sendNextMessage(); // MsgInit with our chain-ids
                drainReplayQueue(newAgent);
            }

            @Override
            public void handshakeError(Reason reason) {
                log.warn("App-peer handshake failed for {}: {}", peer, reason);
            }
        });

        try {
            TCPNodeClient newClient = newSyncAgent != null
                    ? new TCPNodeClient(peer.host(), peer.port(),
                            handshakeAgent, newAgent, newKeepAlive, newSyncAgent)
                    : new TCPNodeClient(peer.host(), peer.port(),
                            handshakeAgent, newAgent, newKeepAlive);
            newClient.start();
            this.client = newClient;
            this.agent = newAgent;
            this.keepAliveAgent = newKeepAlive;
            this.syncAgent = newSyncAgent;
            log.info("App-peer connection started: {}", peer);
        } catch (Exception e) {
            log.warn("App-peer connection to {} failed: {}", peer, e.toString());
            this.client = null;
            this.agent = null;
            this.keepAliveAgent = null;
            this.syncAgent = null;
        }
    }

    private synchronized void drainReplayQueue(AppMsgSubmissionAgent target) {
        long now = System.currentTimeMillis() / 1000;
        AppMessage queued;
        while ((queued = replayQueue.pollFirst()) != null) {
            if (!queued.isExpired(now)) {
                target.enqueueMessage(queued);
            }
        }
    }

    /** Periodic keep-alive heartbeat; no-op when disconnected. */
    void keepAliveTick() {
        KeepAliveAgent ka = keepAliveAgent;
        if (ka != null && isConnected()) {
            try {
                ka.sendNextMessage();
            } catch (Exception e) {
                log.debug("Keep-alive to app peer {} failed: {}", peer, e.toString());
            }
        }
    }

    synchronized void shutdown() {
        shutdown = true;
        disposeClient();
        replayQueue.clear();
    }

    private void disposeClient() {
        TCPNodeClient c = client;
        client = null;
        agent = null;
        keepAliveAgent = null;
        syncAgent = null;
        if (c != null) {
            try {
                c.shutdown();
            } catch (Exception e) {
                log.debug("Error shutting down app-peer client {}: {}", peer, e.toString());
            }
        }
    }
}
