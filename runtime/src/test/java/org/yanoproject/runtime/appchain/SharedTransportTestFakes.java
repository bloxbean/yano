package org.yanoproject.runtime.appchain;


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

}
