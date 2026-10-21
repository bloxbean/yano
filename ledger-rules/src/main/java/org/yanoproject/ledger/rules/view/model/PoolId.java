package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.util.HexUtil;

/**
 * A stake pool id: the blake2b-224 hash of the pool operator's cold verification key.
 *
 * @param hashHex lowercase hex
 */
public record PoolId(String hashHex) {

    public PoolId {
        hashHex = HexStrings.normalize(hashHex, "pool id", HexStrings.HASH28);
    }

    public static PoolId of(byte[] hash) {
        return new PoolId(HexUtil.encodeHexString(hash));
    }

    @Override
    public String toString() {
        return "pool:" + hashHex;
    }
}
