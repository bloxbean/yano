package org.yanoproject.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.impl.SimpleEventBus;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.PipelineConfig;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import org.junit.jupiter.api.Test;
import org.yanoproject.p2p.peer.PeerEndpoint;
import org.yanoproject.p2p.peer.PeerRecoveryReason;
import org.yanoproject.runtime.chain.InMemoryChainState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** On IntersectNotFound a session offers the upstream older local points, then fails for peer recovery. */
class PeerSessionIntersectTest {
    private static final Tip UPSTREAM_TIP = new Tip(new Point(500, "ff".repeat(32)), 50);

    @Test
    void sequentialSyncRestartsAtOlderPointsThenRequestsRecovery() {
        RecordingPeerClient client = new RecordingPeerClient();
        RecordingCallbacks callbacks = new RecordingCallbacks();
        PeerSession session = session(client, callbacks);
        try {
            session.startSequential(point(3), PipelineConfig.defaultClientConfig());

            client.listener.intersactNotFound(UPSTREAM_TIP);
            client.listener.intersactNotFound(UPSTREAM_TIP);
            assertThat(client.syncStarts).containsExactly("sync " + point(3), "sync " + point(2), "sync " + point(1));

            client.listener.intersactNotFound(UPSTREAM_TIP);
            client.listener.intersactNotFound(UPSTREAM_TIP);
            assertThat(client.syncStarts).endsWith("sync " + point(0));
            assertThat(callbacks.recoveries).containsExactly(PeerRecoveryReason.APPLY_FAILED);
        } finally {
            session.stop();
        }
    }

    @Test
    void pipelinedSyncRestartsHeaderSyncAndAnIntersectionStartsTheOffersAfresh() {
        RecordingPeerClient client = new RecordingPeerClient();
        PeerSession session = session(client, new RecordingCallbacks());
        try {
            session.startPipelined(point(3), PipelineConfig.defaultClientConfig());

            client.listener.intersactNotFound(UPSTREAM_TIP);
            client.listener.intersactFound(UPSTREAM_TIP, point(2));
            client.listener.intersactNotFound(UPSTREAM_TIP);

            assertThat(client.syncStarts)
                    .containsExactly("headers " + point(3), "headers " + point(2), "headers " + point(2));
        } finally {
            session.stop();
        }
    }

    @Test
    void aHeaderOnlyCacheAboveTheBodyTipOffersTheBodyTipFirst() {
        RecordingPeerClient client = new RecordingPeerClient();
        InMemoryChainState chain = chain();
        chain.storeBlockHeader(hash(4), 4L, 40L, new byte[] {1});
        PeerSession session = session(client, new RecordingCallbacks(), chain);
        try {
            session.startPipelined(point(4), PipelineConfig.defaultClientConfig());

            client.listener.intersactNotFound(UPSTREAM_TIP);
            client.listener.intersactNotFound(UPSTREAM_TIP);

            assertThat(client.syncStarts)
                    .containsExactly("headers " + point(4), "headers " + point(3), "headers " + point(2));
        } finally {
            session.stop();
        }
    }

    private static PeerSession session(PeerClient client, PeerSessionCallbacks callbacks) {
        return session(client, callbacks, chain());
    }

    private static PeerSession session(PeerClient client, PeerSessionCallbacks callbacks, InMemoryChainState chain) {
        return new PeerSession(new PeerEndpoint("upstream", 3001, 42L), chain, new SimpleEventBus(), callbacks,
                null, (endpoint, startPoint) -> client);
    }

    /** Blocks 0..3, block n at slot 10n. */
    private static InMemoryChainState chain() {
        InMemoryChainState chain = new InMemoryChainState();
        for (long n = 0; n <= 3; n++) {
            chain.storeBlockHeader(hash(n), n, n * 10, new byte[] {1});
            chain.storeBlock(hash(n), n, n * 10, new byte[] {1});
        }
        return chain;
    }

    private static Point point(long n) {
        return new Point(n * 10, HexUtil.encodeHexString(hash(n)));
    }

    private static byte[] hash(long n) {
        byte[] hash = new byte[32];
        hash[31] = (byte) (n + 1);
        return hash;
    }

    private static final class RecordingPeerClient extends PeerClient {
        final List<String> syncStarts = new CopyOnWriteArrayList<>();
        volatile BlockChainDataListener listener;

        RecordingPeerClient() {
            super("upstream", 3001, 42, Point.ORIGIN);
        }

        @Override
        public void connect(BlockChainDataListener listener, TxSubmissionListener txSubmissionListener) {
            this.listener = listener;
        }

        @Override
        public void enableTxSubmission() {
        }

        @Override
        public void startSync(Point from) {
            syncStarts.add("sync " + from);
        }

        @Override
        public void startHeaderSync(Point from, boolean isPipelined) {
            syncStarts.add("headers " + from);
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public void stop() {
        }
    }

    private static final class RecordingCallbacks implements PeerSessionCallbacks {
        final List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

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

        @Override
        public void requestPeerRecovery(PeerRecoveryReason reason) {
            recoveries.add(reason);
        }
    }
}
