package org.yanoproject.ledger.rules.conway.tx;

import java.util.Objects;

/**
 * One transaction output (or the collateral return) as encoded in the body.
 *
 * <p>{@link #slice()} is the output's original encoding, whose length is Haskell's {@code Sized} size used by
 * the minimum-UTxO rule ({@code babbageMinUTxOValue}, Babbage/TxOut.hs:665-689). The address and value are
 * decoded from those bytes: the legacy array form {@code [address, amount, ?datum_hash]} and the Babbage map
 * form {@code {0: address, 1: amount, ?2: datum_option, ?3: script_ref}} are both read.</p>
 *
 * @param index   the output index; for the collateral return, the number of outputs
 *                ({@code mkCollateralTxIn}, Babbage/Collateral.hs:52-60)
 * @param slice   the original bytes of the output
 * @param address the address bytes
 * @param value   the value
 * @param collateralReturn true for the collateral return output
 * @param datumHash        the output's datum hash (legacy element 2, or {@code datum_option [0, hash]}), or null
 * @param scriptRef        the output's reference script (map form, key 3), or null
 */
public record RawOutput(int index, CborSlice slice, byte[] address, LedgerValue value, boolean collateralReturn,
                        byte[] datumHash, RawScript scriptRef) {

    public RawOutput {
        Objects.requireNonNull(slice, "slice");
        address = Objects.requireNonNull(address, "address").clone();
        Objects.requireNonNull(value, "value");
        datumHash = datumHash != null ? datumHash.clone() : null;
    }

    @Override
    public byte[] address() {
        return address.clone();
    }

    @Override
    public byte[] datumHash() {
        return datumHash != null ? datumHash.clone() : null;
    }

    /** @return true when the output carries a reference script */
    public boolean hasScriptRef() {
        return scriptRef != null;
    }

    /** @return the serialised size of the output as received */
    public int size() {
        return slice.length();
    }

    /** Parses one output starting at the reader's position. */
    static RawOutput read(CborReader reader, byte[] tx, int index, boolean collateralReturn) {
        CborSlice slice = reader.readItem();
        CborReader item = new CborReader(tx, slice);
        byte[] address = null;
        LedgerValue value = null;
        byte[] datumHash = null;
        RawScript scriptRef = null;
        if (item.peekMajor() == 4) {
            // Legacy form (Babbage/TxOut.hs:553-576): exactly [address, value] or [address, value, datum_hash].
            long length = item.readArrayHeader();
            if (length != CborReader.INDEFINITE && (length < 2 || length > 3)) {
                throw new TxDecodingException("a legacy output has 2 or 3 elements, found " + length);
            }
            address = item.readDefiniteBytes();
            value = readValue(item);
            if (length == 3 || (length == CborReader.INDEFINITE && item.hasNext(length, 2))) {
                datumHash = item.readDefiniteBytes();
                if (datumHash.length != 32) {
                    throw new TxDecodingException("output " + index + " has a datum hash that is not 32 bytes");
                }
                if (length == CborReader.INDEFINITE && item.hasNext(length, 3)) {
                    throw new TxDecodingException("Excess terms in TxOut");
                }
            }
        } else {
            // Map form (Babbage/TxOut.hs:605-663): a sparse keyed map with keys 0-3, no duplicate, 0 and 1 required.
            long entries = item.readMapHeader();
            boolean[] seen = new boolean[4];
            for (long i = 0; item.hasNext(entries, i); i++) {
                long key = item.readUnsignedLong();
                if (key > 3) {
                    throw new TxDecodingException("unknown output key " + key);
                }
                if (seen[(int) key]) {
                    throw new TxDecodingException("duplicate output key " + key);
                }
                seen[(int) key] = true;
                if (key == 0) {
                    address = item.readDefiniteBytes();
                } else if (key == 1) {
                    value = readValue(item);
                } else if (key == 2) {
                    datumHash = readDatumOption(item, index);
                } else {
                    scriptRef = readScriptRef(item);
                }
            }
        }
        if (address == null || value == null) {
            throw new TxDecodingException("output " + index + " has no address or no value");
        }
        AddressBytes.validate(address);
        return new RawOutput(index, slice, address, value, collateralReturn, datumHash, scriptRef);
    }

    /**
     * {@code datum_option = [0, hash32] / [1, #6.24(bytes .cbor plutus_data)]}.
     *
     * @return the datum hash, or null for an inline datum
     */
    private static byte[] readDatumOption(CborReader reader, int index) {
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("output " + index + ": a datum option is a two-element array");
        }
        long kind = reader.readUnsignedLong();
        byte[] hash = null;
        if (kind == 0) {
            hash = reader.readDefiniteBytes();
            if (hash.length != 32) {
                throw new TxDecodingException("output " + index + " has a datum hash that is not 32 bytes");
            }
        } else if (kind == 1) {
            if (reader.readTag() != 24) {
                throw new TxDecodingException("output " + index + ": an inline datum is wrapped in tag 24");
            }
            // decodeNestedCborBytes (a definite byte string), then makeBinaryData: the bytes must be one
            // well-formed Plutus Data (cardano-ledger-core Plutus/Data.hs:220-239).
            PlutusData.validate(reader.readDefiniteBytes());
        } else {
            throw new TxDecodingException("output " + index + ": unknown datum option " + kind);
        }
        if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
            throw new TxDecodingException("output " + index + ": a datum option is a two-element array");
        }
        return hash;
    }

    /** {@code script_ref = #6.24(bytes .cbor script)}. */
    private static RawScript readScriptRef(CborReader reader) {
        if (reader.readTag() != 24) {
            throw new TxDecodingException("a script reference is wrapped in tag 24");
        }
        CborReader inner = new CborReader(reader.readDefiniteBytes());
        RawScript script = RawScript.readScript(inner);
        if (!inner.atEnd()) {
            throw new TxDecodingException("trailing bytes after a reference script");
        }
        return script;
    }

    /** Reads a {@code value}: {@code coin} or {@code [coin, multiasset<positive_coin>]}. */
    static LedgerValue readValue(CborReader reader) {
        if (reader.peekMajor() == 0) {
            return LedgerValue.ofCoin(reader.readUnsigned());
        }
        long length = reader.readArrayHeader();
        if (length != 2 && length != CborReader.INDEFINITE) {
            throw new TxDecodingException("a multi-asset value is a two-element array");
        }
        LedgerValue value = new LedgerValue(reader.readUnsigned(), MultiAssets.read(reader, false));
        if (length == CborReader.INDEFINITE && !reader.hasNext(length, 2)) {
            return value;
        }
        if (length == CborReader.INDEFINITE) {
            throw new TxDecodingException("a multi-asset value is a two-element array");
        }
        return value;
    }
}
