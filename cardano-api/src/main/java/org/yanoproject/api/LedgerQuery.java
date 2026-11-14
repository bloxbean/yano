package org.yanoproject.api;

import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.api.model.GenesisParameters;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.api.utxo.UtxoState;

import java.util.Map;
import java.util.Optional;

/**
 * Ledger, UTXO, protocol-parameter, and epoch/slot query surface.
 */
public interface LedgerQuery {
    UtxoState getUtxoState();

    default LedgerStateProvider getLedgerStateProvider() {
        return null;
    }

    String getProtocolParameters();

    default Optional<ProtocolParamsSnapshot> getProtocolParameters(int epoch) {
        LedgerStateProvider ledgerStateProvider = getLedgerStateProvider();
        return ledgerStateProvider != null
                ? ledgerStateProvider.getProtocolParameters(epoch)
                : Optional.empty();
    }

    GenesisParameters getGenesisParameters();

    default Map<String, Object> getEpochNonceInfo() {
        return null;
    }

    default String getEpochNonce(int epoch) {
        return null;
    }

    default Map<String, Object> getEpochCalcStatus() {
        return null;
    }

    long slotToUnixTime(long slot);

    /** Converts a canonical slot using the genesis-derived era/epoch schedule. */
    default long slotToEpoch(long slot) {
        throw new UnsupportedOperationException("slot-to-epoch conversion unavailable");
    }

    /** Installs the optional epoch archive capture before startup recovery runs. */
    default void setEpochArchiveStagingSink(
            org.yanoproject.api.archive.EpochArchiveStagingSink sink) {
        throw new UnsupportedOperationException("epoch archive staging is unavailable");
    }
}
