package com.bloxbean.cardano.yano.p2p.peersharing;

import com.bloxbean.cardano.yano.p2p.governor.PeerStoreEntry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayPeerSharingProviderTest {

    @Test
    void returnsNoPeersWhenDisabled() {
        RelayPeerSharingProvider provider = new RelayPeerSharingProvider(
                false,
                "127.0.0.1",
                13338,
                true,
                () -> List.of(new PeerStoreEntry("peer-a", "1.1.1.1", 3001, "test", true, 10)),
                LoggerFactory.getLogger(RelayPeerSharingProviderTest.class));

        assertTrue(provider.peers(10).isEmpty());
    }

    @Test
    void includesAdvertisedPrivateEndpointWhenAllowed() {
        RelayPeerSharingProvider provider = new RelayPeerSharingProvider(
                true,
                "127.0.0.1",
                13338,
                true,
                List::of,
                LoggerFactory.getLogger(RelayPeerSharingProviderTest.class));

        var peers = provider.peers(10);

        assertEquals(1, peers.size());
        assertEquals("127.0.0.1", peers.get(0).getAddress());
        assertEquals(13338, peers.get(0).getPort());
    }

    @Test
    void filtersPrivateEndpointUnlessAllowedAndReturnsPublicStorePeers() {
        RelayPeerSharingProvider provider = new RelayPeerSharingProvider(
                true,
                "127.0.0.1",
                13338,
                false,
                () -> List.of(
                        new PeerStoreEntry("private", "10.0.0.1", 3001, "test", true, 10),
                        new PeerStoreEntry("public", "1.1.1.1", 3002, "test", true, 10)),
                LoggerFactory.getLogger(RelayPeerSharingProviderTest.class));

        var peers = provider.peers(10);

        assertEquals(1, peers.size());
        assertEquals("1.1.1.1", peers.get(0).getAddress());
        assertEquals(3002, peers.get(0).getPort());
    }

    @Test
    void suppressesDuplicateEndpointsAndRespectsRequestedAmount() {
        RelayPeerSharingProvider provider = new RelayPeerSharingProvider(
                true,
                "1.1.1.1",
                3001,
                false,
                () -> List.of(
                        new PeerStoreEntry("duplicate", "1.1.1.1", 3001, "test", true, 10),
                        new PeerStoreEntry("second", "8.8.8.8", 3002, "test", true, 10)),
                LoggerFactory.getLogger(RelayPeerSharingProviderTest.class));

        var peers = provider.peers(1);

        assertEquals(1, peers.size());
        assertEquals("1.1.1.1", peers.get(0).getAddress());
        assertEquals(3001, peers.get(0).getPort());
    }

    @Test
    void autoResolvesSelfWhenAdvertisedHostIsEmptyAndPrivateAddressesAreAllowed() {
        RelayPeerSharingProvider provider = new RelayPeerSharingProvider(
                true,
                "",
                13338,
                true,
                List::of,
                () -> List.of("192.168.1.10"),
                LoggerFactory.getLogger(RelayPeerSharingProviderTest.class));

        var peers = provider.peers(10);

        assertEquals(1, peers.size());
        assertEquals("192.168.1.10", peers.get(0).getAddress());
        assertEquals(13338, peers.get(0).getPort());
    }

    @Test
    void autoResolvesSelfWhenAdvertisedHostIsAuto() {
        RelayPeerSharingProvider provider = new RelayPeerSharingProvider(
                true,
                "auto",
                13338,
                false,
                List::of,
                () -> List.of("8.8.8.8"),
                LoggerFactory.getLogger(RelayPeerSharingProviderTest.class));

        var peers = provider.peers(10);

        assertEquals(1, peers.size());
        assertEquals("8.8.8.8", peers.get(0).getAddress());
        assertEquals(13338, peers.get(0).getPort());
    }

    @Test
    void skipsAutoResolvedPrivateSelfWhenPrivateAddressesAreNotAllowed() {
        RelayPeerSharingProvider provider = new RelayPeerSharingProvider(
                true,
                "",
                13338,
                false,
                () -> List.of(new PeerStoreEntry("public", "8.8.8.8", 3001, "test", true, 10)),
                () -> List.of("192.168.1.10"),
                LoggerFactory.getLogger(RelayPeerSharingProviderTest.class));

        var peers = provider.peers(10);

        assertEquals(1, peers.size());
        assertEquals("8.8.8.8", peers.get(0).getAddress());
        assertEquals(3001, peers.get(0).getPort());
    }
}
