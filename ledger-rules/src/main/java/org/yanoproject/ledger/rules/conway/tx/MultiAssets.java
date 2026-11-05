package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.transaction.raw.RawMultiAsset;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The rules' form of a {@code multiasset<a>} map that {@link HaskellPolicy} has accepted, read by CCL's view: policy id
 * to asset name to quantity, as hex keys. Not checked by the policy: {@code isMultiAssetSmallEnough} (a
 * compact-representation bound far above what a transaction within {@code maxTxSize} can carry).
 */
final class MultiAssets {

    private MultiAssets() {
    }

    static Map<String, Map<String, BigInteger>> of(List<RawMultiAsset> views) {
        TreeMap<String, Map<String, BigInteger>> result = new TreeMap<>();
        for (RawMultiAsset view : views) {
            Map<String, BigInteger> names = new TreeMap<>();
            for (RawMultiAsset.Asset asset : view.assets()) {
                names.put(HexUtil.encodeHexString(asset.name()), asset.quantity());
            }
            result.put(HexUtil.encodeHexString(view.policyId()), names);
        }
        return result;
    }
}
