package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * cardano-client-lib {@link UtxoSupplier} over the node's own UTxO store —
 * inside the node we ARE the backend, so QuickTx tx construction (fee/
 * collateral/change balancing, ex-unit evaluation) runs against local state
 * with no provider round-trips (Iteration 4, ADR 008.4 delivery notes).
 */
final class NodeUtxoSupplier implements UtxoSupplier {

    private final Supplier<UtxoState> utxoStateSupplier;

    NodeUtxoSupplier(Supplier<UtxoState> utxoStateSupplier) {
        this.utxoStateSupplier = utxoStateSupplier;
    }

    @Override
    public List<com.bloxbean.cardano.client.api.model.Utxo> getPage(
            String address, Integer nrOfItems, Integer page, OrderEnum order) {
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState == null) {
            return List.of();
        }
        // CCL pages are 0-based; the node's API is 1-based
        int pageSize = nrOfItems != null ? nrOfItems : UtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH;
        int nodePage = (page != null ? page : 0) + 1;
        return utxoState.getUtxosByAddress(address, nodePage, pageSize).stream()
                .map(NodeUtxoSupplier::toCcl)
                .toList();
    }

    @Override
    public Optional<com.bloxbean.cardano.client.api.model.Utxo> getTxOutput(String txHash, int outputIndex) {
        UtxoState utxoState = utxoStateSupplier.get();
        if (utxoState == null) {
            return Optional.empty();
        }
        return utxoState.getUtxo(new Outpoint(txHash, outputIndex)).map(NodeUtxoSupplier::toCcl);
    }

    /** Node UTxO model → CCL model (asset names are hex on both sides). */
    static com.bloxbean.cardano.client.api.model.Utxo toCcl(Utxo utxo) {
        List<Amount> amounts = new ArrayList<>();
        if (utxo.lovelace() != null) {
            amounts.add(Amount.lovelace(utxo.lovelace()));
        }
        if (utxo.assets() != null) {
            for (AssetAmount asset : utxo.assets()) {
                amounts.add(Amount.asset(asset.policyId(),
                        normalizeAssetName(asset.assetName()), asset.quantity()));
            }
        }
        return com.bloxbean.cardano.client.api.model.Utxo.builder()
                .txHash(utxo.outpoint().txHash())
                .outputIndex(utxo.outpoint().index())
                .address(utxo.address())
                .amount(amounts)
                .dataHash(utxo.datumHash())
                .inlineDatum(utxo.inlineDatum() != null && utxo.inlineDatum().length > 0
                        ? HexUtil.encodeHexString(utxo.inlineDatum()) : null)
                .referenceScriptHash(utxo.referenceScriptHash())
                .build();
    }

    /** CCL treats names without 0x as UTF-8; the store keeps them as hex. */
    private static String normalizeAssetName(String nameHex) {
        if (nameHex == null || nameHex.isEmpty()) {
            return "0x";
        }
        return nameHex.startsWith("0x") ? nameHex : "0x" + nameHex;
    }
}
