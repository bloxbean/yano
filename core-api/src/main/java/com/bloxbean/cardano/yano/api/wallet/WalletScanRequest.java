package com.bloxbean.cardano.yano.api.wallet;

import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.util.List;

/** Version-one scan: after is exclusive, to inclusive; resume outputs describe after. */
public record WalletScanRequest(int version, List<WalletCredential> credentials,
                                WalletChainPoint after, WalletChainPoint to,
                                List<Utxo> knownOutputs) {
    public WalletScanRequest {
        if (version != 1) throw new IllegalArgumentException("Unsupported scan request version");
        if (credentials == null || credentials.isEmpty() || credentials.size() > 200) {
            throw new IllegalArgumentException("Supply between 1 and 200 credentials");
        }
        credentials = List.copyOf(credentials);
        if (after == null) throw new IllegalArgumentException("Explicit after point required; use origin for initial recovery");
        if (after.blockNumber() >= 0 && knownOutputs == null) {
            throw new IllegalArgumentException("Resumed scan requires knownOutputs at the after point");
        }
        if (knownOutputs != null && knownOutputs.size() > 10_000) throw new IllegalArgumentException("Too many known outputs");
        knownOutputs = knownOutputs == null ? List.of() : List.copyOf(knownOutputs);
        if (after.blockNumber() == -1 && !knownOutputs.isEmpty()) throw new IllegalArgumentException("Origin scan seeds its own genesis outputs");
        if (to != null && to.blockNumber() < after.blockNumber()) throw new IllegalArgumentException("End precedes start");
    }
}
