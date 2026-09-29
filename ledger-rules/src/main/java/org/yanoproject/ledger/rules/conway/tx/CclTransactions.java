package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.yanoproject.ledger.rules.util.DefiniteLengthCbor;

import java.util.Arrays;

/**
 * Decodes a transaction with CCL for its structure, tolerating the indefinite-length containers CCL mishandles.
 *
 * <p>Haskell decodes every list, set and map of the body in definite or indefinite length ({@code decodeList},
 * {@code decodeSet} and {@code decodeMap} use {@code decodeListLenOrIndef} / {@code decodeMapLenOrIndef}). CCL's
 * decoder does not everywhere: cbor-java keeps an indefinite array's {@code break} marker as its last item, and
 * {@code PoolRegistration.deserialize} casts it to a byte string (a {@code ClassCastException} wrapped as "CBOR
 * deserialization failed"). Preview transaction {@code 1c09afd80edba3e530fa48fa34f1bc0b2c7999b7e0c76bda2d644444c53e1032}
 * (slot 60896134) registers a pool whose owners and relays are indefinite-length arrays, and the chain accepted it.</p>
 *
 * <p>When the original bytes do not decode, the transaction is decoded from a copy with every item definite-length
 * ({@link DefiniteLengthCbor#normalizeTransaction}), which has the same content. Only the structure is read from the
 * result: the id, hashes and sizes always come from the original bytes ({@link RawTransaction}), and a result is
 * never re-serialised.</p>
 */
public final class CclTransactions {

    private CclTransactions() {
    }

    /**
     * @param txCbor the transaction as received
     * @return the CCL transaction
     * @throws CborDeserializationException the original failure, when neither the bytes nor their definite-length
     *                                      copy decode
     */
    public static Transaction deserialize(byte[] txCbor) throws CborDeserializationException {
        try {
            return Transaction.deserialize(txCbor);
        } catch (CborDeserializationException | RuntimeException original) {
            byte[] normalized;
            try {
                normalized = DefiniteLengthCbor.normalizeTransaction(txCbor);
            } catch (RuntimeException e) {
                throw rethrow(original);
            }
            if (Arrays.equals(normalized, txCbor)) {
                throw rethrow(original);
            }
            try {
                return Transaction.deserialize(normalized);
            } catch (CborDeserializationException | RuntimeException e) {
                throw rethrow(original);
            }
        }
    }

    private static CborDeserializationException rethrow(Exception original) {
        return original instanceof CborDeserializationException cde ? cde
                : new CborDeserializationException("CBOR deserialization failed", original);
    }
}
