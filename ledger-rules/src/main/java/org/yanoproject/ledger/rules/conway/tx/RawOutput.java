package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.FieldType;
import com.bloxbean.cardano.client.transaction.raw.RawValue;

import java.util.Objects;

/**
 * One transaction output (or the collateral return) as encoded in the body.
 *
 * <p>{@link #span()} is the output's original encoding, whose length is Haskell's {@code Sized} size used by
 * the minimum-UTxO rule ({@code babbageMinUTxOValue}, Babbage/TxOut.hs:665-689). The address and value are
 * decoded from those bytes: the legacy array form {@code [address, amount, ?datum_hash]} and the Babbage map
 * form {@code {0: address, 1: amount, ?2: datum_option, ?3: script_ref}} are both read.</p>
 *
 * @param index   the output index; for the collateral return, the number of outputs
 *                ({@code mkCollateralTxIn}, Babbage/Collateral.hs:52-60)
 * @param span    the original bytes of the output
 * @param address the address bytes
 * @param value   the value
 * @param collateralReturn true for the collateral return output
 * @param datumHash        the output's datum hash (legacy element 2, or {@code datum_option [0, hash]}), or null
 * @param inlineDatum      the output's inline datum exactly as encoded (the contents of the {@code #6.24} byte string
 *                         of {@code datum_option [1, …]}), or null. Haskell keeps an inline datum as these bytes
 *                         ({@code BinaryData}, cardano-ledger-core Plutus/Data.hs:220-239), and a script sees the
 *                         {@code Data} decoded from them, map entries in their order; a re-encoding (CCL's canonical
 *                         CBOR sorts map keys) can change that {@code Data}.
 * @param scriptRef        the output's reference script (map form, key 3), or null
 */
public record RawOutput(int index, CborSpan span, byte[] address, LedgerValue value, boolean collateralReturn,
                        byte[] datumHash, byte[] inlineDatum, RawScript scriptRef) {

    public RawOutput {
        Objects.requireNonNull(span, "span");
        address = Objects.requireNonNull(address, "address").clone();
        Objects.requireNonNull(value, "value");
        datumHash = datumHash != null ? datumHash.clone() : null;
        inlineDatum = inlineDatum != null ? inlineDatum.clone() : null;
    }

    @Override
    public byte[] address() {
        return address.clone();
    }

    @Override
    public byte[] datumHash() {
        return datumHash != null ? datumHash.clone() : null;
    }

    @Override
    public byte[] inlineDatum() {
        return inlineDatum != null ? inlineDatum.clone() : null;
    }

    /** @return true when the output carries a reference script */
    public boolean hasScriptRef() {
        return scriptRef != null;
    }

    /** @return the serialised size of the output as received */
    public int size() {
        return span.length();
    }

    /** Reads one output: {@link HaskellPolicy} decides whether it decodes, CCL's reader reads it. */
    static RawOutput read(CborSpan item, int index, boolean collateralReturn) {
        HaskellPolicy.CONWAY.walk(FieldType.TRANSACTION_OUTPUT, item);
        return accepted(com.bloxbean.cardano.client.transaction.raw.RawOutput.of(item), index, collateralReturn);
    }

    /** The output at {@code index}, which {@link HaskellPolicy} has accepted. */
    static RawOutput accepted(com.bloxbean.cardano.client.transaction.raw.RawOutput output, int index,
                              boolean collateralReturn) {
        return new RawOutput(index, output.span(), output.addressBytes().orElseThrow(),
                value(RawValue.of(output.value())), collateralReturn, output.datumHash().orElse(null),
                output.inlineDatumBytes().orElse(null),
                output.scriptRefBytes().map(script -> RawScript.readScript(StrictCbor.span(script))).orElse(null));
    }

    /** Reads a {@code value}: {@link HaskellPolicy} decides whether it decodes, CCL's reader reads it. */
    static LedgerValue readValue(CborSpan item) {
        HaskellPolicy.CONWAY.walk(FieldType.VALUE, item);
        return value(RawValue.of(item));
    }

    private static LedgerValue value(RawValue value) {
        return new LedgerValue(value.coin(), MultiAssets.of(value.multiAssets()));
    }
}
