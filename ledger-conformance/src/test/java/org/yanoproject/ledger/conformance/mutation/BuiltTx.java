package org.yanoproject.ledger.conformance.mutation;

import com.bloxbean.cardano.client.transaction.spec.Transaction;

import java.math.BigInteger;

/**
 * A built transaction.
 *
 * @param cbor   the signed transaction bytes
 * @param tx     the CCL transaction the bytes were serialised from
 * @param minFee the minimum fee of exactly these bytes ({@code minFeeA · size + minFeeB + script fee})
 */
public record BuiltTx(byte[] cbor, Transaction tx, BigInteger minFee) {

    public BuiltTx {
        cbor = cbor.clone();
    }

    @Override
    public byte[] cbor() {
        return cbor.clone();
    }

    public BigInteger fee() {
        return tx.getBody().getFee();
    }
}
