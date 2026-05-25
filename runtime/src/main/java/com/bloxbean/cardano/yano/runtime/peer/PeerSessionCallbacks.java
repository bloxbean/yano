package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;

/**
 * Runtime callbacks needed by the active peer session listener.
 */
public interface PeerSessionCallbacks {
    void resumeBodyFetchOnHeaderFlow();

    void updateSyncProgress();

    void notifyServerNewBlockStored();

    void onIntersectionFound();

    void maybeFastTransitionToSteadyState(Tip remoteTip);

    void handleChainSyncRollback(Point point);

    default void onPeerDisconnected() {
    }

    default void requestPeerRecovery(PeerRecoveryReason reason) {
    }
}
