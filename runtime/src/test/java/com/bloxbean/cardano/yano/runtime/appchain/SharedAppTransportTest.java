package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yano.p2p.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.p2p.peer.PeerEndpoint;
import com.bloxbean.cardano.yano.runtime.appchain.SharedTransportTestFakes.FakePeerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared app transport (ADR 005 M1 unification): arming L1 sessions with the
 * app-layer protocols, routing enqueues, and single-owner catch-up routing.
 */
class SharedAppTransportTest {

    private static final String PEER_KEY = "apphost:13337";

    private SharedAppTransport transport;
    private FakePeerClient client;

    @BeforeEach
    void setUp() {
        AppMsgSubmissionConfig unionConfig = AppMsgSubmissionConfig.builder()
                .chainIds(Set.of("c1", "c2")).build();
        transport = new SharedAppTransport(unionConfig, Set.of(PEER_KEY),
                LoggerFactory.getLogger("test"));
        client = new FakePeerClient();
    }

    private PeerClientFactory factoryReturning(FakePeerClient value) {
        return (endpoint, startPoint) -> value;
    }

    @Test
    void wrapArmsOnlyAppPeerEndpoints() {
        PeerClientFactory wrapped = transport.wrap(factoryReturning(client));

        wrapped.create(new PeerEndpoint("otherhost", 3001, 42), null);
        assertThat(client.appMsgEnabled.get()).isZero();
        assertThat(client.appChainSyncEnabled.get()).isZero();

        wrapped.create(new PeerEndpoint("APPHOST", 13337, 42), null); // case-insensitive
        assertThat(client.appMsgEnabled.get()).isEqualTo(1);
        assertThat(client.appChainSyncEnabled.get()).isEqualTo(1);
    }

    @Test
    void enqueueRoutesToSharedAgentOnlyWhenUp() {
        transport.wrap(factoryReturning(client))
                .create(new PeerEndpoint("apphost", 13337, 42), null);
        AppMessage message = SharedTransportTestFakes.message("m1");

        client.down();
        assertThat(transport.isUp(PEER_KEY)).isFalse();
        assertThat(transport.enqueue(PEER_KEY, message)).isFalse();
        assertThat(client.manager.msgAgent.enqueued).isEmpty();

        client.up();
        assertThat(transport.isUp(PEER_KEY)).isTrue();
        assertThat(transport.enqueue(PEER_KEY, message)).isTrue();
        assertThat(client.manager.msgAgent.enqueued).containsExactly(message);
    }

    @Test
    void catchUpHasOneOwnerAndDeliversToIt() {
        transport.wrap(factoryReturning(client))
                .create(new PeerEndpoint("apphost", 13337, 42), null);
        client.up();

        AtomicReference<List<byte[]>> received = new AtomicReference<>();
        AppPeerClient.CatchUpHandler handler = (peerId, blocks, tip) -> received.set(blocks);

        assertThat(transport.requestCatchUp(PEER_KEY, "c1", 1, 50, handler)).isTrue();
        assertThat(client.manager.syncAgent.requestedChains).containsExactly("c1");

        // A second chain's request while the first is in flight is refused
        assertThat(transport.requestCatchUp(PEER_KEY, "c2", 1, 50, (p, b, t) -> {
        })).isFalse();

        // The reply goes to the owner, which frees the slot
        client.manager.syncAgent.fireBlocks(List.of(new byte[]{1}), 7);
        assertThat(received.get()).hasSize(1);
        assertThat(transport.requestCatchUp(PEER_KEY, "c2", 1, 50, handler)).isTrue();
        assertThat(client.manager.syncAgent.requestedChains).containsExactly("c1", "c2");
    }

    @Test
    void failedRequestReleasesTheOwnerSlot() {
        transport.wrap(factoryReturning(client))
                .create(new PeerEndpoint("apphost", 13337, 42), null);
        client.up();
        client.manager.syncAgent.accept = false;

        AppPeerClient.CatchUpHandler handler = (p, b, t) -> {
        };
        assertThat(transport.requestCatchUp(PEER_KEY, "c1", 1, 50, handler)).isFalse();
        // Slot must be free for the retry
        client.manager.syncAgent.accept = true;
        assertThat(transport.requestCatchUp(PEER_KEY, "c1", 1, 50, handler)).isTrue();
    }

    @Test
    void newSessionReplacesHandleAndClearsStaleOwner() {
        PeerClientFactory wrapped = transport.wrap(factoryReturning(client));
        wrapped.create(new PeerEndpoint("apphost", 13337, 42), null);
        client.up();
        assertThat(transport.requestCatchUp(PEER_KEY, "c1", 1, 50, (p, b, t) -> {
        })).isTrue();

        // Supervisor restart: a NEW client (fresh manager/agents) for the endpoint
        FakePeerClient client2 = new FakePeerClient();
        transport.wrap(factoryReturning(client2))
                .create(new PeerEndpoint("apphost", 13337, 42), null);
        client2.up();

        // The old session's unanswered request must not block the new session
        assertThat(transport.requestCatchUp(PEER_KEY, "c2", 1, 50, (p, b, t) -> {
        })).isTrue();
        assertThat(client2.manager.syncAgent.requestedChains).containsExactly("c2");

        // Traffic flows to the NEW agent
        AppMessage message = SharedTransportTestFakes.message("m2");
        assertThat(transport.enqueue(PEER_KEY, message)).isTrue();
        assertThat(client2.manager.msgAgent.enqueued).containsExactly(message);
        assertThat(client.manager.msgAgent.enqueued).isEmpty();
    }

    @Test
    void unownedCatchUpReplyIsDroppedQuietly() {
        transport.wrap(factoryReturning(client))
                .create(new PeerEndpoint("apphost", 13337, 42), null);
        client.up();
        // No request in flight — a stray reply must not throw
        client.manager.syncAgent.fireBlocks(List.of(new byte[]{1}), 9);
    }
}
