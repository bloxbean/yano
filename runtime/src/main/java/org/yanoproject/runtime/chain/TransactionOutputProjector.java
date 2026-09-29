package org.yanoproject.runtime.chain;

import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.conway.tx.RawOutput;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxDecodingException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared projection used by mempool and block-local UTXO overlays.
 *
 * <p>An inline datum keeps its original bytes ({@link RawOutput#inlineDatum()}), as the canonical UTxO store does.
 * CCL's re-encoding is canonical CBOR, which sorts a map's keys, so a later script spending the output would see a
 * different {@code Data} (ADR-056 Phase 7c).</p>
 */
@Slf4j
public final class TransactionOutputProjector {
    private TransactionOutputProjector() {
    }

    /**
     * @param txHash  the transaction id
     * @param txBytes the transaction as received
     * @param tx      the transaction decoded from {@code txBytes}
     * @return the transaction's outputs, in index order, each inline datum with its original bytes
     */
    public static List<Utxo> projectOutputs(String txHash, byte[] txBytes, Transaction tx) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        if (outputs == null || outputs.isEmpty()) {
            return List.of();
        }
        List<RawOutput> raw = rawOutputs(txHash, txBytes, tx);
        List<Utxo> projected = new ArrayList<>(outputs.size());
        for (int index = 0; index < outputs.size(); index++) {
            byte[] inlineDatum = raw != null ? raw.get(index).inlineDatum() : null;
            projected.add(project(txHash, index, outputs.get(index), inlineDatum));
        }
        return projected;
    }

    /** @return the outputs as the strict decoder reads them, or null when it rejects the bytes */
    private static List<RawOutput> rawOutputs(String txHash, byte[] txBytes, Transaction tx) {
        try {
            return RawTransaction.parse(txBytes, tx).outputs();
        } catch (TxDecodingException e) {
            log.warn("Transaction {} does not decode strictly ({}); its inline datums use CCL's re-encoding", txHash,
                    e.getMessage());
            return null;
        }
    }

    /**
     * @param inlineDatum the output's inline datum exactly as encoded, or null to use CCL's re-encoding (only for an
     *                    output whose bytes are not at hand)
     */
    public static Utxo project(String txHash, int outputIndex, TransactionOutput output, byte[] inlineDatum) {
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
        byte[] datum = inlineDatum != null ? inlineDatum.clone()
                : output.getInlineDatum() != null ? output.getInlineDatum().serializeToBytes() : null;
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
                datum,
                scriptRef,
                referenceScriptHash,
                false,
                0,
                0,
                null);
    }
}
