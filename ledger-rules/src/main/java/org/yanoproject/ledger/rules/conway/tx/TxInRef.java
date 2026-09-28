package org.yanoproject.ledger.rules.conway.tx;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.view.model.Outpoints;

import java.util.Comparator;
import java.util.Objects;

/**
 * A transaction input as the body names it, ordered like Haskell's {@code TxIn}: by transaction id bytes, then
 * by output index ({@code Ord TxIn}, derived from {@code TxIn TxId TxIx}). Sets of inputs in failures are
 * reported in this order.
 *
 * @param txIdHex the transaction id, lowercase hex
 * @param index   the output index
 */
public record TxInRef(String txIdHex, int index) implements Comparable<TxInRef> {

    private static final Comparator<TxInRef> ORDER =
            Comparator.comparing(TxInRef::txIdHex).thenComparingInt(TxInRef::index);

    public TxInRef {
        Objects.requireNonNull(txIdHex, "txIdHex");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0: " + index);
        }
    }

    /** @return the view key for this input */
    public Outpoint outpoint() {
        return Outpoints.of(txIdHex, index);
    }

    @Override
    public int compareTo(TxInRef other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return txIdHex + "#" + index;
    }
}
