package com.bloxbean.cardano.yano.runtime.chain;

import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Shared projection used by mempool and block-local UTXO overlays. */
public final class TransactionOutputProjector {
    private TransactionOutputProjector() {
    }

    public static Utxo project(String txHash, int outputIndex, TransactionOutput output) {
        if (output == null || output.getValue() == null) {
            throw new IllegalArgumentException("transaction output or value is null");
        }

        BigInteger lovelace = output.getValue().getCoin() != null
                ? output.getValue().getCoin() : BigInteger.ZERO;
        List<AssetAmount> assets = new ArrayList<>();
        if (output.getValue().getMultiAssets() != null) {
            output.getValue().getMultiAssets().forEach(multiAsset -> {
                if (multiAsset.getAssets() == null) return;
                multiAsset.getAssets().forEach(asset -> {
                    String assetName = asset.getNameAsHex();
                    if (assetName != null && assetName.startsWith("0x")) {
                        assetName = assetName.substring(2);
                    }
                    assets.add(new AssetAmount(
                            multiAsset.getPolicyId(), assetName, asset.getValue()));
                });
            });
        }

        String datumHash = output.getDatumHash() != null
                ? HexUtil.encodeHexString(output.getDatumHash()) : null;
        byte[] inlineDatum = output.getInlineDatum() != null
                ? output.getInlineDatum().serializeToBytes() : null;
        String scriptRef = output.getScriptRef() != null
                ? HexUtil.encodeHexString(output.getScriptRef()) : null;
        String referenceScriptHash = null;
        if (output.getScriptRef() != null) {
            try {
                var script = ReferenceScriptUtil.deserializeScriptRef(output.getScriptRef());
                referenceScriptHash = HexUtil.encodeHexString(script.getScriptHash());
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid reference script", e);
            }
        }

        return new Utxo(
                new Outpoint(txHash, outputIndex),
                output.getAddress(),
                lovelace,
                List.copyOf(assets),
                datumHash,
                inlineDatum,
                scriptRef,
                referenceScriptHash,
                false,
                0,
                0,
                null);
    }
}
