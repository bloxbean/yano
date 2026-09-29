package org.yanoproject.runtime.validation;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.runtime.chain.TransactionOutputProjector;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts between the mempool's query model ({@link Utxo}) and the ledger view's {@link UtxoEntry}.
 * {@link Utxo#scriptRef()} is CCL's reference-script CBOR ({@link TransactionOutputProjector}), so the
 * conversion keeps reference scripts exact; the inline datum bytes are carried over as they are.
 */
public final class UtxoConversions {

    private UtxoConversions() {
    }

    public static UtxoEntry toEntry(Outpoint outpoint, Utxo utxo) {
        Map<String, List<Asset>> byPolicy = new LinkedHashMap<>();
        if (utxo.assets() != null) {
            for (AssetAmount asset : utxo.assets()) {
                String name = asset.assetName() == null ? "" : asset.assetName();
                byPolicy.computeIfAbsent(asset.policyId(), ignored -> new ArrayList<>())
                        .add(new Asset("0x" + name, asset.quantity()));
            }
        }
        List<MultiAsset> multiAssets = new ArrayList<>();
        byPolicy.forEach((policy, assets) -> multiAssets.add(new MultiAsset(policy, assets)));
        BigInteger lovelace = utxo.lovelace() != null ? utxo.lovelace() : BigInteger.ZERO;
        byte[] inlineDatum = utxo.inlineDatum();
        PlutusData datum;
        try {
            datum = inlineDatum != null ? PlutusData.deserialize(inlineDatum) : null;
        } catch (Exception e) {
            throw new IllegalArgumentException("undecodable inline datum of " + outpoint + ": " + e.getMessage(), e);
        }
        TransactionOutput output = new TransactionOutput(utxo.address(), new Value(lovelace, multiAssets),
                utxo.datumHash() != null ? HexUtil.decodeHexString(utxo.datumHash()) : null, datum,
                utxo.scriptRef() != null ? HexUtil.decodeHexString(utxo.scriptRef()) : null);
        return new UtxoEntry(outpoint, output, inlineDatum);
    }

    public static Utxo toUtxo(UtxoEntry entry) {
        Utxo projected = TransactionOutputProjector.project(entry.outpoint().txHash(), entry.outpoint().index(),
                entry.output());
        byte[] inline = entry.inlineDatumCbor();
        if (inline == null) {
            return projected;
        }
        return new Utxo(projected.outpoint(), projected.address(), projected.lovelace(), projected.assets(),
                projected.datumHash(), inline, projected.scriptRef(), projected.referenceScriptHash(),
                projected.collateralReturn(), projected.slot(), projected.blockNumber(), projected.blockHash());
    }
}
