package com.bloxbean.cardano.yano.runtime.peer;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;

/**
 * Internal runtime callbacks needed by the active peer session listener.
 *
 * <p>This interface is implemented by the sync subsystem and is not part of
 * the public plugin/API surface.</p>
 */
public interface PeerSessionCallbacks {
    void resumeBodyFetchOnHeaderFlow();

    void updateSyncProgress(long slot, long blockNumber);

    void notifyServerNewBlockStored();

    void onIntersectionFound();

    void maybeFastTransitionToSteadyState(Tip remoteTip);

    void handleChainSyncRollback(Point point);

    default void onPeerDisconnected() {
    }

    default void requestPeerRecovery(PeerRecoveryReason reason) {
    }
}
