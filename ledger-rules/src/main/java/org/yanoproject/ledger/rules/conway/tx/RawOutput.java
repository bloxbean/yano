package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.util.List;
import java.util.Map;
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

    /** Parses one output. */
    static RawOutput read(CborSpan item, int index, boolean collateralReturn) {
        byte[] address = null;
        LedgerValue value = null;
        byte[] datumHash = null;
        byte[] inlineDatum = null;
        RawScript scriptRef = null;
        if (StrictCbor.major(item) == 4) {
            // Legacy form (Babbage/TxOut.hs:553-576): exactly [address, value] or [address, value, datum_hash].
            List<CborSpan> fields = StrictCbor.array(item);
            if (fields.size() < 2 || fields.size() > 3) {
                throw new TxDecodingException("a legacy output has 2 or 3 elements, found " + fields.size());
            }
            address = StrictCbor.definiteBytes(fields.get(0));
            value = readValue(fields.get(1));
            if (fields.size() == 3) {
                datumHash = StrictCbor.definiteBytes(fields.get(2));
                if (datumHash.length != 32) {
                    throw new TxDecodingException("output " + index + " has a datum hash that is not 32 bytes");
                }
            }
        } else {
            // Map form (Babbage/TxOut.hs:605-663): a sparse keyed map with keys 0-3, no duplicate, 0 and 1 required.
            boolean[] seen = new boolean[4];
            for (Map.Entry<CborSpan, CborSpan> entry : StrictCbor.map(item)) {
                long key = StrictCbor.unsignedLong(entry.getKey());
                if (key > 3) {
                    throw new TxDecodingException("unknown output key " + key);
                }
                if (seen[(int) key]) {
                    throw new TxDecodingException("duplicate output key " + key);
                }
                seen[(int) key] = true;
                CborSpan field = entry.getValue();
                if (key == 0) {
                    address = StrictCbor.definiteBytes(field);
                } else if (key == 1) {
                    value = readValue(field);
                } else if (key == 2) {
                    byte[][] datum = readDatumOption(field, index);
                    datumHash = datum[0];
                    inlineDatum = datum[1];
                } else {
                    scriptRef = readScriptRef(field);
                }
            }
        }
        if (address == null || value == null) {
            throw new TxDecodingException("output " + index + " has no address or no value");
        }
        AddressBytes.validate(address);
        return new RawOutput(index, item, address, value, collateralReturn, datumHash, inlineDatum, scriptRef);
    }

    /**
     * {@code datum_option = [0, hash32] / [1, #6.24(bytes .cbor plutus_data)]}.
     *
     * @return {datum hash, inline datum bytes}: one of them is null
     */
    private static byte[][] readDatumOption(CborSpan item, int index) {
        List<CborSpan> fields = StrictCbor.array(item, 2,
                "output " + index + ": a datum option is a two-element array");
        long kind = StrictCbor.unsignedLong(fields.get(0));
        byte[] hash = null;
        byte[] inline = null;
        if (kind == 0) {
            hash = StrictCbor.definiteBytes(fields.get(1));
            if (hash.length != 32) {
                throw new TxDecodingException("output " + index + " has a datum hash that is not 32 bytes");
            }
        } else if (kind == 1) {
            if (fields.get(1).tag() != 24) {
                throw new TxDecodingException("output " + index + ": an inline datum is wrapped in tag 24");
            }
            // decodeNestedCborBytes (a definite byte string), then makeBinaryData: the bytes must be exactly one
            // well-formed Plutus Data (decodeFull', cardano-ledger-core Plutus/Data.hs:154-173).
            inline = StrictCbor.definiteBytes(fields.get(1).untag());
            PlutusData.validate(inline);
        } else {
            throw new TxDecodingException("output " + index + ": unknown datum option " + kind);
        }
        return new byte[][]{hash, inline};
    }

    /** {@code script_ref = #6.24(bytes .cbor script)}. */
    private static RawScript readScriptRef(CborSpan item) {
        if (item.tag() != 24) {
            throw new TxDecodingException("a script reference is wrapped in tag 24");
        }
        return RawScript.readScript(StrictCbor.span(StrictCbor.definiteBytes(item.untag())));
    }

    /** Reads a {@code value}: {@code coin} or {@code [coin, multiasset<positive_coin>]}. */
    static LedgerValue readValue(CborSpan item) {
        if (StrictCbor.major(item) == 0) {
            return LedgerValue.ofCoin(StrictCbor.unsigned(item));
        }
        List<CborSpan> fields = StrictCbor.array(item, 2, "a multi-asset value is a two-element array");
        return new LedgerValue(StrictCbor.unsigned(fields.get(0)), MultiAssets.read(fields.get(1), false));
    }
}
