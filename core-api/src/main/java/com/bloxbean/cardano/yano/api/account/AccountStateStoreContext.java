package com.bloxbean.cardano.yano.api.account;

import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Context passed to {@link AccountStateStoreProvider#isAvailable} and {@link AccountStateStoreProvider#create}.
 *
 * @param chainState         runtime chain state (may implement {@link com.bloxbean.cardano.yano.api.db.RocksDbAccess})
 * @param config             runtime globals (yano.* properties)
 * @param logger             logger for provider use
 * @param epochParamProvider protocol parameter provider for deposit amounts
 */
public record AccountStateStoreContext(
        ChainState chainState,
        Map<String, Object> config,
        Logger logger,
        EpochParamProvider epochParamProvider
) {}
