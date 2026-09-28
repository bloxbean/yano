package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;

import org.yanoproject.api.utxo.model.Outpoint;

import java.util.Objects;

/**
 * An unspent output as the rules see it.
 *
 * <p>The CCL {@link TransactionOutput} carries the address, value, datum hash, inline datum and
 * reference script. It is a mutable CCL bean: holders of a {@code UtxoEntry} must treat it as
 * read-only.</p>
 *
 * @param outpoint the output reference; its tx hash is lowercase hex
 * @param output   the output
 */
public record UtxoEntry(Outpoint outpoint, TransactionOutput output) {

    public UtxoEntry {
        Objects.requireNonNull(outpoint, "outpoint");
        Objects.requireNonNull(output, "output");
        outpoint = Outpoints.normalize(outpoint);
    }
}
