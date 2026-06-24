package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;

/**
 * Read-only chain block access for components that need replay/reconciliation
 * without receiving the mutable chain-state implementation.
 */
public interface ChainBlockReader {
    ChainTip getLocalTip();

    byte[] getBlockByNumber(long blockNumber);

    Era getBlockEra(long blockNumber);
}
