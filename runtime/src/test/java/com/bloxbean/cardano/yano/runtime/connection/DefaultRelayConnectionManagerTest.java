package com.bloxbean.cardano.yano.runtime.connection;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.N2NVersionData;
import com.bloxbean.cardano.yaci.core.protocol.txsubmission.TxSubmissionListener;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.runtime.peer.PeerClientFactory;
import com.bloxbean.cardano.yano.runtime.peer.PeerEndpoint;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRelayConnectionManagerTest {
    private static final AcceptVersion ACCEPT_VERSION = new AcceptVersion(
            14,
            new N2NVersionData(1L, false, 1, true));

    @Test
    void enforcesMaxInboundConnections() {
        DefaultRelayConnectionManager manager = manager(1, 5);

        assertTrue(manager.reserveInbound("in-1", ConnectionKey.of("192.0.2.1", 40_001)).accepted());

        var rejected = manager.reserveInbound("in-2", ConnectionKey.of("192.0.2.2", 40_002));

        assertFalse(rejected.accepted());
        assertEquals("max inbound connections reached", rejected.reason());
        assertEquals(1, manager.snapshot().inboundConnectionCount());
        assertEquals(1, manager.snapshot().rejectedInboundConnections());

        manager.markClosed("in-1", "closed");

        assertEquals(0, manager.snapshot().inboundConnectionCount());
        assertTrue(manager.reserveInbound("in-3", ConnectionKey.of("192.0.2.2", 40_003)).accepted());
    }

    @Test
    void enforcesMaxInboundConnectionsPerIp() {
        DefaultRelayConnectionManager manager = manager(10, 1);

        assertTrue(manager.reserveInbound("in-1", ConnectionKey.of("192.0.2.10", 40_001)).accepted());

        var rejected = manager.reserveInbound("in-2", ConnectionKey.of("192.0.2.10", 40_002));

        assertFalse(rejected.accepted());
        assertEquals("max inbound connections per IP reached", rejected.reason());
        assertEquals(1, manager.snapshot().inboundConnectionCount());
        assertEquals(1, manager.snapshot().rejectedInboundConnections());
    }

    @Test
    void recordsEstablishedInboundCapabilities() {
        DefaultRelayConnectionManager manager = manager(10, 5);
        List<RelayConnectionEvent> events = new ArrayList<>();
        manager.addListener(events::add);

        manager.reserveInbound("in-1", ConnectionKey.of("192.0.2.20", 40_001));
        manager.markInboundEstablished("in-1", ACCEPT_VERSION);

        RelayConnectionSnapshot snapshot = manager.snapshot();
        assertEquals(1, snapshot.inboundConnectionCount());
        assertEquals(1, snapshot.establishedConnectionCount());
        assertEquals(1, snapshot.connections().size());
        assertTrue(snapshot.connections().getFirst().usableForPeerSharing());
        RelayConnectionEvent established = events.getLast();
        assertEquals(ConnectionState.ESTABLISHED, established.state());
        assertEquals(14, established.capabilities().negotiatedVersion());
        assertTrue(established.capabilities().peerSharing());
        assertTrue(established.capabilities().query());
    }

    @Test
    void normalizesIpv6HostKeys() {
        ConnectionKey bracketed = ConnectionKey.of("[::1]", 3001);
        ConnectionKey expanded = ConnectionKey.of("0:0:0:0:0:0:0:1", 3001);

        assertEquals(expanded, bracketed);
    }

    @Test
    void wrapsOutboundPeerClientLifecycle() {
        DefaultRelayConnectionManager manager = manager(10, 5);
        FakePeerClient fakeClient = new FakePeerClient();
        PeerClientFactory factory = manager.wrapPeerClientFactory((endpoint, startPoint) -> fakeClient);

        PeerClient client = factory.create(new PeerEndpoint("example.com", 3001, 1L), Point.ORIGIN);
        assertEquals(1, manager.snapshot().outboundConnectionCount());
        assertEquals(1, manager.snapshot().connectingConnectionCount());

        client.connect(new BlockChainDataListener() { }, (TxSubmissionListener) null);

        assertEquals(1, manager.snapshot().outboundConnectionCount());
        assertEquals(1, manager.snapshot().establishedConnectionCount());

        client.stop();

        assertEquals(0, manager.snapshot().outboundConnectionCount());
        assertEquals(0, manager.snapshot().establishedConnectionCount());
    }

    @Test
    void rejectsDuplicateOutboundDialToSameEndpoint() {
        DefaultRelayConnectionManager manager = manager(10, 5);
        PeerEndpoint endpoint = new PeerEndpoint("example.com", 3001, 1L);
        PeerClientFactory factory = manager.wrapPeerClientFactory((peerEndpoint, startPoint) -> new FakePeerClient());

        factory.create(endpoint, Point.ORIGIN);

        assertThrows(IllegalStateException.class, () -> factory.create(endpoint, Point.ORIGIN));
        assertEquals(0, manager.snapshot().failedOutboundConnections());
    }

    private static DefaultRelayConnectionManager manager(int maxInbound, int maxPerIp) {
        return new DefaultRelayConnectionManager(maxInbound, maxPerIp,
                LoggerFactory.getLogger(DefaultRelayConnectionManagerTest.class));
    }

    private static final class FakePeerClient extends PeerClient {
        private boolean running;

        private FakePeerClient() {
            super("example.com", 3001, 1L, Point.ORIGIN);
        }

        @Override
        public void connect(BlockChainDataListener blockChainDataListener,
                            TxSubmissionListener txSubmissionListener) {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public Optional<AcceptVersion> getProtocolVersion() {
            return Optional.of(ACCEPT_VERSION);
        }
    }
}
