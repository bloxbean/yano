package org.yanoproject.runtime.devnet.spi;

import org.yanoproject.api.model.DevnetRollbackResult;
import org.yanoproject.api.model.DevnetRollbackTarget;

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
