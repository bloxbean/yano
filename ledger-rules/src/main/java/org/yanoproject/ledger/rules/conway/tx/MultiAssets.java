package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decoding of {@code multiasset<a>} maps, {@code {policy_id => {asset_name => a}}}, as Conway's
 * {@code decodeMultiAsset} does at version 9+ (Mary/Value.hs:310-335): no duplicate policy or asset name
 * ({@code decodeMap} enforces it from version 9, Decoder.hs:810-830), no empty asset map, no zero quantity,
 * asset names of at most 32 bytes (Mary/Value.hs:126-135). Output values carry {@code Word64} quantities; the
 * mint field {@code Int64} ones ({@code decodeIntegerBounded64}).
 *
 * <p>Not checked: {@code isMultiAssetSmallEnough} (a compact-representation bound far above what a transaction
 * within {@code maxTxSize} can carry).</p>
 */
final class MultiAssets {

    private static final int POLICY_ID_LENGTH = 28;
    private static final int MAX_ASSET_NAME_LENGTH = 32;
    private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private MultiAssets() {
    }

    /**
     * @param signed true for the mint field (non-zero {@code Int64}), false for output values (non-zero
     *               {@code Word64})
     * @return policy id → asset name → quantity (hex keys)
     */
    static Map<String, Map<String, BigInteger>> read(CborSpan item, boolean signed) {
        TreeMap<String, Map<String, BigInteger>> result = new TreeMap<>();
        for (Map.Entry<CborSpan, CborSpan> policyEntry : StrictCbor.map(item)) {
            byte[] policy = StrictCbor.definiteBytes(policyEntry.getKey());
            if (policy.length != POLICY_ID_LENGTH) {
                throw new TxDecodingException("policy id of " + policy.length + " bytes");
            }
            String policyHex = HexUtil.encodeHexString(policy);
            if (result.containsKey(policyHex)) {
                throw new TxDecodingException("duplicate policy id " + policyHex);
            }
            Map<String, BigInteger> names = new TreeMap<>();
            for (Map.Entry<CborSpan, CborSpan> asset : StrictCbor.map(policyEntry.getValue())) {
                byte[] name = StrictCbor.definiteBytes(asset.getKey());
                if (name.length > MAX_ASSET_NAME_LENGTH) {
                    throw new TxDecodingException("asset name of " + name.length + " bytes");
                }
                CborSpan amount = asset.getValue();
                BigInteger quantity = signed ? StrictCbor.integer(amount) : StrictCbor.unsigned(amount);
                if (signed && (quantity.compareTo(INT64_MIN) < 0 || quantity.compareTo(INT64_MAX) > 0)) {
                    throw new TxDecodingException("overflow when decoding mint field: " + quantity);
                }
                if (quantity.signum() == 0) {
                    throw new TxDecodingException("MultiAsset cannot contain zeros");
                }
                if (names.put(HexUtil.encodeHexString(name), quantity) != null) {
                    throw new TxDecodingException("duplicate asset name " + HexUtil.encodeHexString(name));
                }
            }
            if (names.isEmpty()) {
                throw new TxDecodingException("Empty Assets are not allowed");
            }
            result.put(policyHex, names);
        }
        return result;
    }
}
