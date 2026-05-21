package com.bloxbean.cardano.yano.api.util;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.math.BigInteger;

/**
 * Helpers for interpreting block bytes already persisted in {@code ChainState}.
 */
public final class StoredBlockUtil {
    private StoredBlockUtil() {
    }

    /**
     * Return true only when a stored block body is explicitly tagged as Byron.
     * <p>
     * Ledger-state replay intentionally skips Byron bodies because live sync does
     * not feed Byron block-derived UTXO/account events into those stores. Unknown
     * or malformed CBOR must not be treated as Byron; replay should fail closed
     * for non-Byron corruption instead of silently skipping data.
     */
    public static boolean isStoredByronBlock(Era storedEra, byte[] blockBytes) {
        if (storedEra != null && storedEra != Era.Byron) {
            return false;
        }
        if (blockBytes == null) {
            return false;
        }

        try {
            DataItem item = CborSerializationUtil.deserializeOne(blockBytes);
            if (!(item instanceof Array array) || array.getDataItems().isEmpty()) {
                return false;
            }
            DataItem first = array.getDataItems().get(0);
            if (!(first instanceof UnsignedInteger eraTag)) {
                return false;
            }
            BigInteger tag = eraTag.getValue();
            return BigInteger.ZERO.equals(tag) || BigInteger.ONE.equals(tag);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
