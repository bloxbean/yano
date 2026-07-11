package com.bloxbean.cardano.yano.p2p.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.PeerClient;

/**
 * Creates Yaci peer clients for session startup.
 */
@FunctionalInterface
public interface PeerClientFactory {
    PeerClient create(PeerEndpoint endpoint, Point startPoint);
}
