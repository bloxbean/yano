package org.yanoproject.runtime.tx;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.runtime.config.InMemoryDevnetGenesis;

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
