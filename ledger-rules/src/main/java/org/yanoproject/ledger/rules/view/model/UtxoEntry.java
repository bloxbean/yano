package org.yanoproject.ledger.rules.view.model;

import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;

import org.yanoproject.api.utxo.model.Outpoint;

import java.util.Arrays;
import java.util.Objects;

/**
 * An unspent output as the rules see it.
 *
 * <p>The CCL {@link TransactionOutput} carries the address, value, datum hash, inline datum and
 * reference script. It is a mutable CCL bean: holders of a {@code UtxoEntry} must treat it as
 * read-only.</p>
 *
 * <p>Byte-exactness. {@link TransactionOutput#getScriptRef()} holds the reference script exactly as
 * it appeared on chain (the {@code script} CBOR inside the tag-24 wrapper). The inline datum,
 * however, is a decoded {@code PlutusData} that CCL re-encodes on serialisation, which need not
 * reproduce the on-chain bytes. {@link #inlineDatumCbor()} therefore carries the inline datum
 * exactly as stored when the source knows it; {@code null} means "not available" (for example an
 * output produced by a transaction being validated), not "no inline datum".</p>
 *
 * @param outpoint        the output reference; its tx hash is lowercase hex
 * @param output          the output
 * @param inlineDatumCbor the inline datum CBOR exactly as on chain, or {@code null} when the source
 *                        does not have it or the output has no inline datum
 */
public record UtxoEntry(Outpoint outpoint, TransactionOutput output, byte[] inlineDatumCbor) {

    public UtxoEntry {
        Objects.requireNonNull(outpoint, "outpoint");
        Objects.requireNonNull(output, "output");
        outpoint = Outpoints.normalize(outpoint);
        inlineDatumCbor = inlineDatumCbor != null ? inlineDatumCbor.clone() : null;
    }

    /** An entry without stored inline-datum bytes. */
    public UtxoEntry(Outpoint outpoint, TransactionOutput output) {
        this(outpoint, output, null);
    }

    /** @return a copy of the stored inline datum bytes, or {@code null} when not available */
    @Override
    public byte[] inlineDatumCbor() {
        return inlineDatumCbor != null ? inlineDatumCbor.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UtxoEntry other
                && outpoint.equals(other.outpoint)
                && output.equals(other.output)
                && Arrays.equals(inlineDatumCbor, other.inlineDatumCbor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(outpoint, output, Arrays.hashCode(inlineDatumCbor));
    }

    @Override
    public String toString() {
        return "UtxoEntry[outpoint=" + outpoint + ", output=" + output
                + ", inlineDatumCbor=" + (inlineDatumCbor != null ? inlineDatumCbor.length + " bytes" : "n/a")
                + "]";
    }
}
