package com.bloxbean.cardano.yano.api.wallet;

import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.util.List;

/** NDJSON record. Only a done record commits a successfully scanned cursor. */
public record WalletScanEvent(String type, WalletChainPoint point, WalletIndexCoverage coverage,
                              String txHash, Boolean valid, List<Outpoint> inputs,
                              List<Utxo> outputs, String error) {
    public static WalletScanEvent progress(String type, WalletChainPoint point) {
        return new WalletScanEvent(type, point, null, null, null, null, null, null);
    }
}
