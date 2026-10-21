package org.yanoproject.ledger.rules;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import org.yanoproject.ledger.rules.util.CborItems;

import java.util.Arrays;
import java.util.Objects;

/**
 * Transaction id from the raw transaction bytes.
 *
 * <p>The id is blake2b-256 of the body bytes <em>exactly as transmitted</em>. This class slices the
 * first element of the top-level transaction array out of the original bytes and never
 * re-serialises, so non-canonical encodings (key order, indefinite lengths, non-minimal integers)
 * keep their id. Deserialising with CCL and serialising again would change such bytes and the id.</p>
 */
public final class TxIdentity {

    private TxIdentity() {
    }

    /**
     * @param txCbor a transaction: {@code [body, witnesses, is_valid, auxiliary_data]}
     * @return the original body bytes
     * @throws IllegalArgumentException if the bytes are not a CBOR array whose first element is a map
     */
    public static byte[] bodyBytes(byte[] txCbor) {
        Objects.requireNonNull(txCbor, "txCbor");
        if (txCbor.length == 0) {
            throw new IllegalArgumentException("Empty transaction bytes");
        }
        int initial = txCbor[0] & 0xff;
        if (initial >>> 5 != 4) {
            throw new IllegalArgumentException("Transaction is not a CBOR array");
        }
        int headerLength = switch (initial & 0x1f) {
            case 24 -> 2;
            case 25 -> 3;
            case 26 -> 5;
            case 27 -> 9;
            case 28, 29, 30 -> throw new IllegalArgumentException("Malformed transaction array header");
            default -> 1; // 0..23 inline, 31 indefinite
        };
        if (headerLength >= txCbor.length) {
            throw new IllegalArgumentException("Transaction has no body");
        }
        if ((initial & 0x1f) != 31) {
            long count = 0;
            if ((initial & 0x1f) < 24) {
                count = initial & 0x1f;
            } else {
                for (int i = 1; i < headerLength; i++) {
                    count = (count << 8) | (txCbor[i] & 0xff);
                }
            }
            if (count == 0) {
                throw new IllegalArgumentException("Transaction array is empty");
            }
        }
        if ((txCbor[headerLength] & 0xff) >>> 5 != 5) {
            throw new IllegalArgumentException("Transaction body is not a CBOR map");
        }
        int end = CborItems.skip(txCbor, headerLength);
        return Arrays.copyOfRange(txCbor, headerLength, end);
    }

    /** @return blake2b-256 of the original body bytes */
    public static byte[] txId(byte[] txCbor) {
        return Blake2bUtil.blake2bHash256(bodyBytes(txCbor));
    }

    /** @return the transaction id as lowercase hex */
    public static String txIdHex(byte[] txCbor) {
        return HexUtil.encodeHexString(txId(txCbor));
    }
}
