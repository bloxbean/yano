package org.yanoproject.ledger.amaru.wire;

import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.List;

/**
 * Re-encodes a resolved UTxO output for a request (INTERFACE.md, "Which output bytes must be exact").
 *
 * <p>Yano's {@link UtxoEntry} holds a decoded CCL output, so the output is written again as a
 * post-Alonzo map {@code {0: address, 1: value, ?2: datum_option, ?3: #6.24(bytes script_ref)}}. What
 * Amaru needs byte-exact is kept exact:</p>
 * <ul>
 *   <li>the <b>inline datum</b> comes from {@link UtxoEntry#inlineDatumCbor()} (the stored on-chain
 *       bytes); only when a source does not have them is CCL's re-encoding used, which can change the
 *       datum hash of a non-canonically encoded datum;</li>
 *   <li>the <b>reference script</b> is CCL's {@code scriptRef}, which is already the on-chain script
 *       CBOR; only the tag-24 wrapper is rewritten, which INTERFACE.md allows;</li>
 *   <li>the <b>address</b> bytes are CCL's own (bech32 or base58 decoding is lossless).</li>
 * </ul>
 * <p>The value is written from its decoded form (coin, or {@code [coin, multiasset]}), and the envelope
 * (legacy array or map, key order, lengths) is not preserved; neither affects a verdict for a resolved
 * output.</p>
 */
public final class UtxoOutputEncoder {

    private UtxoOutputEncoder() {
    }

    /**
     * @throws IllegalArgumentException when the output cannot be encoded (for example an address CCL
     *                                  cannot turn back into bytes)
     */
    public static byte[] encode(UtxoEntry entry) {
        TransactionOutput output = entry.output();
        byte[] address;
        try {
            address = AddressUtil.addressToBytes(output.getAddress());
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot encode the address of " + entry.outpoint() + ": "
                    + e.getMessage(), e);
        }
        byte[] inlineDatum = entry.inlineDatumCbor();
        if (inlineDatum == null && output.getInlineDatum() != null) {
            inlineDatum = output.getInlineDatum().serializeToBytes();
        }
        boolean datum = inlineDatum != null || output.getDatumHash() != null;
        boolean script = output.getScriptRef() != null;

        CborWriter w = new CborWriter(128 + (inlineDatum != null ? inlineDatum.length : 0)
                + (script ? output.getScriptRef().length : 0));
        w.map(2 + (datum ? 1 : 0) + (script ? 1 : 0));
        w.uint(0).bytes(address);
        w.uint(1);
        value(w, output.getValue());
        if (inlineDatum != null) {
            w.uint(2).array(2).uint(1).tag(24).bytes(inlineDatum);
        } else if (output.getDatumHash() != null) {
            w.uint(2).array(2).uint(0).bytes(output.getDatumHash());
        }
        if (script) {
            w.uint(3).tag(24).bytes(output.getScriptRef());
        }
        return w.toByteArray();
    }

    private static void value(CborWriter w, Value value) {
        BigInteger coin = value != null && value.getCoin() != null ? value.getCoin() : BigInteger.ZERO;
        List<MultiAsset> assets = value != null ? value.getMultiAssets() : null;
        if (assets == null || assets.isEmpty()) {
            w.uint(coin);
            return;
        }
        w.array(2).uint(coin).map(assets.size());
        for (MultiAsset policy : assets) {
            w.bytes(HexUtil.decodeHexString(policy.getPolicyId()));
            List<Asset> tokens = policy.getAssets() != null ? policy.getAssets() : List.of();
            w.map(tokens.size());
            for (Asset token : tokens) {
                w.bytes(token.getNameAsBytes()).uint(token.getValue());
            }
        }
    }
}
