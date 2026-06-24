package com.bloxbean.cardano.yano.app.api.accounts;

import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugSnapshotResourceTest {
    @Test
    void adaPotReturnsUnavailableWhenDebugStoreIsUnavailable() {
        DebugSnapshotResource resource = resourceWith(debugAccess(null, null));

        var response = resource.getAdaPot(1);

        assertEquals(503, response.getStatus());
    }

    @Test
    void utxoBalanceReturnsUnavailableWhenDebugUtxoStateIsUnavailable() {
        DebugSnapshotResource resource = resourceWith(debugAccess(null, null));

        var response = resource.getUtxoBalance("abcd");

        assertEquals(503, response.getStatus());
    }

    @Test
    void utxoBalanceReturnsUnavailableWhenDebugUtxoStateIsDisabled() {
        DebugSnapshotResource resource = resourceWith(debugAccess(null, disabledUtxoState()));

        var response = resource.getUtxoBalance("abcd");

        assertEquals(503, response.getStatus());
    }

    private static DebugSnapshotResource resourceWith(DebugLedgerStateAccess debugAccess) {
        DebugSnapshotResource resource = new DebugSnapshotResource();
        resource.debugLedgerStateAccess = debugAccess;
        return resource;
    }

    private static DebugLedgerStateAccess debugAccess(DefaultAccountStateStore store, UtxoState utxoState) {
        return new DebugLedgerStateAccess() {
            @Override
            public Optional<DefaultAccountStateStore> getDefaultAccountStateStore() {
                return Optional.ofNullable(store);
            }

            @Override
            public UtxoState getUtxoState() {
                return utxoState;
            }
        };
    }

    private static UtxoState disabledUtxoState() {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
                return List.of();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.empty();
            }

            @Override
            public boolean isEnabled() {
                return false;
            }
        };
    }
}
