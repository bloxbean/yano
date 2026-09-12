package org.yanoproject.runtime.debug;

import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;

import java.util.Optional;

/**
 * Narrow internal access for debug ledger-state endpoints.
 */
public interface DebugLedgerStateAccess {
    Optional<DefaultAccountStateStore> getDefaultAccountStateStore();

    UtxoState getUtxoState();
}
