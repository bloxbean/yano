package com.bloxbean.cardano.yano.p2p.connection;

import com.bloxbean.cardano.yaci.core.common.TxBodyType;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.helper.AppProtocolManager;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.p2p.peer.PeerEndpoint;

import java.util.Objects;
import java.util.Optional;

final class ConnectionTrackingPeerClientFactory implements PeerClientFactory {
    private final PeerClientFactory delegate;
    private final DefaultRelayConnectionManager manager;

    ConnectionTrackingPeerClientFactory(PeerClientFactory delegate, DefaultRelayConnectionManager manager) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public PeerClient create(PeerEndpoint endpoint, Point startPoint) {
        String connectionId = manager.reserveOutbound(endpoint);
        try {
            PeerClient delegateClient = delegate.create(endpoint, startPoint);
            return new TrackingPeerClient(endpoint, startPoint, delegateClient, manager, connectionId);
        } catch (RuntimeException | Error e) {
            manager.markOutboundFailed(connectionId, e);
            throw e;
        }
    }

    private static final class TrackingPeerClient extends PeerClient {
        private final PeerClient delegate;
        private final DefaultRelayConnectionManager manager;
        private final String connectionId;

        private TrackingPeerClient(PeerEndpoint endpoint,
                                   Point startPoint,
                                   PeerClient delegate,
                                   DefaultRelayConnectionManager manager,
                                   String connectionId) {
            super(endpoint.host(), endpoint.port(), endpoint.protocolMagic(), startPoint);
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.manager = Objects.requireNonNull(manager, "manager");
            this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        }

        @Override
        public void connect(BlockChainDataListener blockChainDataListener,
                            TxSubmissionListener txSubmissionListener) {
            try {
                delegate.connect(blockChainDataListener, txSubmissionListener);
                manager.markOutboundEstablished(
                        connectionId,
                        delegate.getProtocolVersion().orElse(null));
            } catch (RuntimeException | Error e) {
                manager.markOutboundFailed(connectionId, e);
                throw e;
            }
        }

        @Override
        public void fetch(Point from, Point to) {
            delegate.fetch(from, to);
        }

        @Override
        public void startSync(Point from) {
            delegate.startSync(from);
        }

        @Override
        public void startSync(Point from, boolean isPipelined) {
            delegate.startSync(from, isPipelined);
        }

        @Override
        public void startHeaderSync(Point from) {
            delegate.startHeaderSync(from);
        }

        @Override
        public void startHeaderSync(Point from, boolean isPipelined) {
            delegate.startHeaderSync(from, isPipelined);
        }

        @Override
        public Optional<Tip> getLatestTip() {
            return delegate.getLatestTip();
        }

        @Override
        public void sendKeepAliveMessage(int cookie) {
            delegate.sendKeepAliveMessage(cookie);
        }

        @Override
        public int getLastKeepAliveResponseCookie() {
            return delegate.getLastKeepAliveResponseCookie();
        }

        @Override
        public long getLastKeepAliveResponseTime() {
            return delegate.getLastKeepAliveResponseTime();
        }

        @Override
        public void stop() {
            try {
                delegate.stop();
            } finally {
                manager.markClosed(connectionId, "closed");
            }
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public void addTxSubmissionListener(TxSubmissionListener txSubmissionListener) {
            delegate.addTxSubmissionListener(txSubmissionListener);
        }

        @Override
        public void submitTxBytes(String txHash, byte[] txBytes, TxBodyType txBodyType) {
            delegate.submitTxBytes(txHash, txBytes, txBodyType);
        }

        @Override
        public void setTxMaxQueueSize(int txMaxQueueSize) {
            delegate.setTxMaxQueueSize(txMaxQueueSize);
        }

        @Override
        public void enableTxSubmission() {
            delegate.enableTxSubmission();
        }

        @Override
        public void enableAppMsg() {
            delegate.enableAppMsg();
        }

        @Override
        public void enableAppMsg(
                com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig appMsgConfig) {
            // Forward BOTH overloads: running the config overload on the
            // wrapper's inherited state while getAppProtocolManager() returns
            // the delegate's manager splits the app-layer setup across two
            // objects (found by the shared-transport gate).
            delegate.enableAppMsg(appMsgConfig);
        }

        @Override
        public void enableAppChainSync() {
            delegate.enableAppChainSync();
        }

        @Override
        public AppProtocolManager getAppProtocolManager() {
            return delegate.getAppProtocolManager();
        }

        @Override
        public void pauseChainSync() {
            delegate.pauseChainSync();
        }

        @Override
        public void resumeChainSync() {
            delegate.resumeChainSync();
        }

        @Override
        public void pauseBlockFetch() {
            delegate.pauseBlockFetch();
        }

        @Override
        public void resumeBlockFetch() {
            delegate.resumeBlockFetch();
        }

        @Override
        public boolean isChainSyncPaused() {
            return delegate.isChainSyncPaused();
        }

        @Override
        public boolean isBlockFetchPaused() {
            return delegate.isBlockFetchPaused();
        }
    }
}
