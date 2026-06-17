package com.bloxbean.cardano.yano.runtime.tx;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;

/**
 * Runtime state needed to create transaction validation/evaluation services.
 */
public interface TransactionBootstrapContext {
    YanoConfig config();

    UtxoState utxoState();

    LedgerStateProvider ledgerStateProvider();

    EpochParamProvider epochParamProvider();

    ChainTip localTip();

    long resolvedGenesisTimestamp();

    default InMemoryDevnetGenesis inMemoryDevnetGenesis() {
        return null;
    }
}
