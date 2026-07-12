package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appchainsync.AppChainSyncClientAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionAgent;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.AppProtocolManager;
import com.bloxbean.cardano.yaci.helper.PeerClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hand-rolled fakes for the shared-transport tests (mockito's inline mock
 * maker cannot instrument current JDK classfiles here, and hand-rolled fakes
 * document the exact seams anyway).
 */
final class SharedTransportTestFakes {

    private SharedTransportTestFakes() {
    }

    /** Records enqueued messages; scriptable acceptance. */
    static final class FakeMsgAgent extends AppMsgSubmissionAgent {
        final List<AppMessage> enqueued = new ArrayList<>();
        boolean accept = true;

        FakeMsgAgent() {
            super(AppMsgSubmissionConfig.builder().chainIds(Set.of("c1")).build());
        }

        @Override
        public boolean enqueueMessage(AppMessage message) {
            if (accept) {
                enqueued.add(message);
            }
            return accept;
        }
    }

    /** Records range requests; scriptable acceptance; can fire replies. */
    static final class FakeSyncAgent extends AppChainSyncClientAgent {
        final List<String> requestedChains = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean requestRange(String chainId, long fromHeight, long toHeight) {
            if (accept) {
                requestedChains.add(chainId);
            }
            return accept;
        }

        void fireBlocks(List<byte[]> blocks, long tipHeight) {
            getAgentListeners().forEach(l -> l.blocksReceived(blocks, tipHeight));
        }
    }

    /** Scriptable negotiation flag; hands out the fake agents. */
    static final class FakeManager extends AppProtocolManager {
        final FakeMsgAgent msgAgent = new FakeMsgAgent();
        final FakeSyncAgent syncAgent = new FakeSyncAgent();
        boolean negotiated;

        @Override
        public boolean isAppLayerNegotiated() {
            return negotiated;
        }

        @Override
        public AppMsgSubmissionAgent getAppMsgSubmissionAgent() {
            return msgAgent;
        }

        @Override
        public AppChainSyncClientAgent getAppChainSyncAgent() {
            return syncAgent;
        }
    }

    /** Offline PeerClient: records arming calls, scriptable liveness. */
    static final class FakePeerClient extends PeerClient {
        final FakeManager manager = new FakeManager();
        final AtomicInteger appMsgEnabled = new AtomicInteger();
        final AtomicInteger appChainSyncEnabled = new AtomicInteger();
        boolean running;

        FakePeerClient() {
            super("fake", 1, 42, Point.ORIGIN);
        }

        @Override
        public void enableAppMsg(AppMsgSubmissionConfig config) {
            appMsgEnabled.incrementAndGet();
        }

        @Override
        public void enableAppChainSync() {
            appChainSyncEnabled.incrementAndGet();
        }

        @Override
        public AppProtocolManager getAppProtocolManager() {
            return manager;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        /** Session fully usable for app traffic. */
        void up() {
            running = true;
            manager.negotiated = true;
        }

        void down() {
            running = false;
        }
    }

    /** Scriptable SharedAppTransport for link tests. */
    static final class FakeTransport extends SharedAppTransport {
        boolean up;
        boolean acceptEnqueue = true;
        boolean acceptCatchUp = true;
        final List<AppMessage> enqueued = new ArrayList<>();
        final List<String> catchUpChains = new ArrayList<>();

        FakeTransport() {
            super(AppMsgSubmissionConfig.builder().chainIds(Set.of("c1")).build(),
                    Set.of("apphost:13337"), org.slf4j.LoggerFactory.getLogger("fake"));
        }

        @Override
        boolean isUp(String endpointKey) {
            return up;
        }

        @Override
        boolean enqueue(String endpointKey, AppMessage message) {
            if (up && acceptEnqueue) {
                enqueued.add(message);
                return true;
            }
            return false;
        }

        @Override
        boolean requestCatchUp(String endpointKey, String chainId, long fromHeight, long toHeight,
                               AppPeerClient.CatchUpHandler handler) {
            if (up && acceptCatchUp) {
                catchUpChains.add(chainId);
                return true;
            }
            return false;
        }
    }

    /** Recording AppPeerLink used as the shared link's fallback. */
    static final class FakeFallbackLink implements AppPeerLink {
        final List<AppMessage> enqueued = new ArrayList<>();
        final List<String> catchUpChains = new ArrayList<>();
        final AtomicInteger connectTicks = new AtomicInteger();
        final AtomicInteger keepAliveTicks = new AtomicInteger();
        boolean shutdownCalled;
        boolean connected = true;

        @Override
        public String peerId() {
            return "fallback";
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void enqueue(AppMessage message) {
            enqueued.add(message);
        }

        @Override
        public boolean requestCatchUp(String chainId, long fromHeight, long toHeight) {
            catchUpChains.add(chainId);
            return true;
        }

        @Override
        public void ensureConnectedAsync() {
            connectTicks.incrementAndGet();
        }

        @Override
        public void keepAliveTick() {
            keepAliveTicks.incrementAndGet();
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }

        @Override
        public String transport() {
            return "dedicated";
        }
    }

    /** Minimal live AppMessage (unexpired, no signature — transport-only tests). */
    static AppMessage message(String body) {
        return AppMessage.builder()
                .messageId(new byte[32])
                .chainId("c1")
                .topic("t")
                .sender(new byte[32])
                .senderSeq(1)
                .expiresAt(System.currentTimeMillis() / 1000 + 600)
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }
}
