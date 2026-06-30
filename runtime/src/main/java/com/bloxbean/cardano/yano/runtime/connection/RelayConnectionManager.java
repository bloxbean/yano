package com.bloxbean.cardano.yano.runtime.connection;

import com.bloxbean.cardano.yaci.core.network.server.ServerConnectionListener;
import com.bloxbean.cardano.yano.runtime.peer.PeerClientFactory;

public interface RelayConnectionManager {
    ServerConnectionListener yaciServerConnectionListener();

    PeerClientFactory wrapPeerClientFactory(PeerClientFactory delegate);

    RelayConnectionSnapshot snapshot();

    void addListener(RelayConnectionListener listener);

    void removeListener(RelayConnectionListener listener);
}
