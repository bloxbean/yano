package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.PipelineConfig;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.runtime.chain.InMemoryChainState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerSessionHealthTest {

    @Test
    void statusRefreshesKeepAliveTimestampFromPeerClient() throws Exception {
        PeerSession session = newSession();
        MockPeerClient peerClient = new MockPeerClient();
        peerClient.lastKeepAliveResponseTime = System.currentTimeMillis() - 100;
        setPeerClient(session, peerClient);

        PeerSessionStatus status = session.getStatus();

        assertEquals(peerClient.lastKeepAliveResponseTime, status.lastKeepAliveResponseAtMillis());
        assertTrue(status.keepAliveAgeMillis() >= 0);
    }

    @Test
    void stopClearsBodyFetchInProgressHealth() {
        PeerSession session = newSession();
        session.getPeerHealth().markBodyFetchStarted(System.currentTimeMillis());

        session.stop();

        PeerSessionStatus status = session.getStatus();
        assertEquals(PeerSessionState.STOPPED, status.state());
        assertFalse(status.bodyFetchInProgress());
        assertEquals(0L, status.bodyFetchStartedAtMillis());
    }

    @Test
    void quiesceNetworkForRecoveryStopsPeerWithoutMarkingStopped() throws Exception {
        PeerSession session = newSession();
        MockPeerClient peerClient = new MockPeerClient();
        setPeerClient(session, peerClient);
        session.getPeerHealth().markState(PeerSessionState.RUNNING);
        session.getPeerHealth().markBodyFetchStarted(System.currentTimeMillis());

        session.quiesceNetworkForRecovery();

        assertTrue(peerClient.stopCalled);
        PeerSessionStatus status = session.getStatus();
        assertEquals(PeerSessionState.STOPPING, status.state());
        assertFalse(status.bodyFetchInProgress());
    }

    @Test
    void startupFailureStopsSessionAndClearsBodyFetchState() throws Exception {
        PeerSession session = newSession();
        setPeerClient(session, new FailingConnectPeerClient());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> session.startSequential(Point.ORIGIN, PipelineConfig.defaultClientConfig()));

        assertEquals("connect failed", error.getMessage());
        PeerSessionStatus status = session.getStatus();
        assertEquals(PeerSessionState.STOPPED, status.state());
        assertEquals(PeerRecoveryReason.UNKNOWN, status.lastRecoveryReason());
        assertFalse(status.bodyFetchInProgress());
        assertEquals(null, status.terminalFailureMessage());

        session.stop();
        PeerSessionStatus afterStop = session.getStatus();
        assertEquals(PeerSessionState.STOPPED, afterStop.state());
        assertEquals(PeerRecoveryReason.UNKNOWN, afterStop.lastRecoveryReason());
    }

    @Test
    void createsPeerClientThroughFactory() {
        PeerEndpoint endpoint = new PeerEndpoint("relay-2", 3002, 42L);
        TrackingPeerClientFactory factory = new TrackingPeerClientFactory();
        PeerSession session = new PeerSession(
                endpoint,
                new InMemoryChainState(),
                new SimpleEventBus(),
                new NoopCallbacks(),
                null,
                factory);

        session.startSequential(Point.ORIGIN, PipelineConfig.defaultClientConfig());

        assertEquals(endpoint, factory.endpoint);
        assertEquals(Point.ORIGIN, factory.startPoint);
        assertEquals(PeerSessionState.RUNNING, session.getStatus().state());
    }

    private PeerSession newSession() {
        return new PeerSession(
                "relay-1",
                3001,
                1L,
                new InMemoryChainState(),
                new SimpleEventBus(),
                new NoopCallbacks(),
                null);
    }

    private void setPeerClient(PeerSession session, PeerClient peerClient) throws Exception {
        Field field = PeerSession.class.getDeclaredField("peerClient");
        field.setAccessible(true);
        field.set(session, peerClient);
    }

    private static class NoopCallbacks implements PeerSessionCallbacks {
        @Override
        public void resumeBodyFetchOnHeaderFlow() {
        }

        @Override
        public void updateSyncProgress(long slot, long blockNumber) {
        }

        @Override
        public void notifyServerNewBlockStored() {
        }

        @Override
        public void onIntersectionFound() {
        }

        @Override
        public void maybeFastTransitionToSteadyState(Tip remoteTip) {
        }

        @Override
        public void handleChainSyncRollback(Point point) {
        }
    }

    private static class MockPeerClient extends PeerClient {
        private long lastKeepAliveResponseTime;
        private boolean stopCalled;

        MockPeerClient() {
            super("mock-host", 3001, 1, Point.ORIGIN);
        }

        @Override
        public long getLastKeepAliveResponseTime() {
            return lastKeepAliveResponseTime;
        }

        @Override
        public void stop() {
            stopCalled = true;
        }
    }

    private static class NoopStartPeerClient extends MockPeerClient {
        @Override
        public void connect(BlockChainDataListener blockChainDataListener, TxSubmissionListener txSubmissionListener) {
        }

        @Override
        public void enableTxSubmission() {
        }

        @Override
        public void startSync(Point from) {
        }
    }

    private static class FailingConnectPeerClient extends MockPeerClient {
        @Override
        public void connect(BlockChainDataListener blockChainDataListener, TxSubmissionListener txSubmissionListener) {
            throw new IllegalStateException("connect failed");
        }
    }

    private static class TrackingPeerClientFactory implements PeerClientFactory {
        private PeerEndpoint endpoint;
        private Point startPoint;

        @Override
        public PeerClient create(PeerEndpoint endpoint, Point startPoint) {
            this.endpoint = endpoint;
            this.startPoint = startPoint;
            return new NoopStartPeerClient();
        }
    }
}
