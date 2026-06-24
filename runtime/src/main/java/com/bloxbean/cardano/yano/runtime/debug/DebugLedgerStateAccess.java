package com.bloxbean.cardano.yano.runtime.debug;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;

import java.util.Optional;

/**
 * Narrow internal access for debug ledger-state endpoints.
 */
public interface DebugLedgerStateAccess {
    Optional<DefaultAccountStateStore> getDefaultAccountStateStore();

    UtxoState getUtxoState();
}
