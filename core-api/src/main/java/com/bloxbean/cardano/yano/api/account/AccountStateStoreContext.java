package com.bloxbean.cardano.yano.api.account;

import com.bloxbean.cardano.yano.api.ChainBlockReader;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.db.RocksDbAccess;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Context passed to {@link AccountStateStoreProvider#isAvailable} and {@link AccountStateStoreProvider#create}.
 *
 * @param chainBlocks        read-only chain block access for reconciliation
 * @param rocksDbAccess      optional RocksDB access for RocksDB-backed stores
 * @param config             runtime globals (yano.* properties)
 * @param logger             logger for provider use
 * @param epochParamProvider protocol parameter provider for deposit amounts
 */
public record AccountStateStoreContext(
        ChainBlockReader chainBlocks,
        RocksDbAccess rocksDbAccess,
        Map<String, Object> config,
        Logger logger,
        EpochParamProvider epochParamProvider
) {}
