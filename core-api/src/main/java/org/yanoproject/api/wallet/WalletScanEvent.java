package org.yanoproject.api.wallet;

import org.yanoproject.api.chain.ChainPoint;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;

import java.util.List;

/** Only done with complete=true commits a cursor; incomplete is a terminal partial result. */
public record WalletScanEvent(String type, ChainPoint point, WalletIndexCoverage coverage,
                              String txHash, Boolean valid, List<Outpoint> inputs,
                              List<Utxo> outputs, String error, Boolean complete) {
    public WalletScanEvent(String type, ChainPoint point, WalletIndexCoverage coverage,
                           String txHash, Boolean valid, List<Outpoint> inputs, List<Utxo> outputs, String error) {
        this(type, point, coverage, txHash, valid, inputs, outputs, error, null);
    }
    public static WalletScanEvent progress(String type, ChainPoint point) {
        return new WalletScanEvent(type, point, null, null, null, null, null, null);
    }
}
