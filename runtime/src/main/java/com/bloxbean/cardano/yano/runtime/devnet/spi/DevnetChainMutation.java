package com.bloxbean.cardano.yano.runtime.devnet.spi;

import com.bloxbean.cardano.yano.api.model.DevnetRollbackResult;
import com.bloxbean.cardano.yano.api.model.DevnetRollbackTarget;

/**
 * Devnet-only chain mutation port.
 */
public interface DevnetChainMutation {
    /**
     * Rolls the devnet chain back to the resolved target.
     *
     * @param target rollback target
     * @return resulting chain tip
     */
    DevnetRollbackResult rollback(DevnetRollbackTarget target);
}
