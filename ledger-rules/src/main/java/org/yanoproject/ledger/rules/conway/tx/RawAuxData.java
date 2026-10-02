package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The auxiliary data, decoded as Conway's {@code AlonzoTxAuxData} decoder does before protocol version 12
 * (Alonzo/TxAuxData.hs:213-300, {@code decodeTxAuxDataByTokenType}):
 *
 * <ul>
 *   <li>a map: Shelley metadata only;</li>
 *   <li>an array: Allegra {@code [metadata, [native_script]]} (exactly two fields);</li>
 *   <li>tag 259: a sparse keyed map, no duplicate key: 0 metadata, 1 native scripts, 2/3/4 Plutus V1/V2/V3 scripts
 *       ({@code guardPlutus}: PlutusV4, key 5, is not a Conway language), any other key is invalid.</li>
 * </ul>
 *
 * <p>Metadata is {@code Map Word64 Metadatum} (no duplicate label) and each {@code Metadatum}
 * ({@code decodeMetadatum}, Metadata.hs:151-230) is an integer of at most 64 bits in magnitude (major type 0 or 1,
 * no big-number tags), a byte string or a UTF-8 text string of at most 64 bytes (definite or indefinite), a list, or
 * a map (a list of pairs, duplicates allowed). Anything else does not decode: at the pinned revision the 64-byte
 * limits are decoding failures, not {@code InvalidMetadata}. What {@code UTXOW} still checks
 * ({@code validateAlonzoTxAuxData}, :360-373) is that every Plutus script here is well formed. This class checks the
 * shape and the metadata; the scripts themselves are read once, with CCL's view ({@code RawTx.auxScripts()}), and
 * decoded as Haskell does by {@link RawScript} ({@link RawTransaction#auxScripts()}). A metadatum is checked without
 * recursion, in linear time, whatever its nesting depth.</p>
 */
public final class RawAuxData {

    private static final int AUX_DATA_TAG = 259;
    private static final int MAX_METADATUM_BYTES = 64;

    private RawAuxData() {
    }

    /**
     * Checks the shape and the metadata; the scripts are decoded by {@link RawScript}.
     *
     * @param auxData the auxiliary data, as encoded
     * @throws TxDecodingException when Haskell would not decode it
     */
    public static void validate(CborSpan auxData) {
        switch (StrictCbor.major(auxData)) {
            case 5 -> metadata(auxData);
            case 4 -> {
                List<CborSpan> fields = StrictCbor.array(auxData);
                if (fields.size() != 2) {
                    throw new TxDecodingException("Allegra auxiliary data has 2 elements, found " + fields.size());
                }
                metadata(fields.get(0));
                StrictCbor.array(fields.get(1));
            }
            case 6 -> {
                if (auxData.tag() != AUX_DATA_TAG) {
                    throw new TxDecodingException("Alonzo auxiliary data carries tag 259");
                }
                Set<Long> seen = new HashSet<>();
                for (Map.Entry<CborSpan, CborSpan> entry : StrictCbor.map(auxData.untag())) {
                    long key = StrictCbor.unsignedLong(entry.getKey());
                    if (!seen.add(key)) {
                        throw new TxDecodingException("Duplicate key: " + key + " in auxiliary data");
                    }
                    switch ((int) Math.min(key, 6)) {
                        case 0 -> metadata(entry.getValue());
                        case 1 -> StrictCbor.array(entry.getValue());
                        case 2, 3, 4 -> StrictCbor.array(entry.getValue());
                        case 5 -> throw new TxDecodingException("PlutusV4 is not supported in Conway");
                        default -> throw new TxDecodingException("unknown auxiliary data key " + key);
                    }
                }
            }
            default -> throw new TxDecodingException("Failed to decode AlonzoTxAuxData");
        }
    }

    /** {@code Map Word64 Metadatum}. */
    private static void metadata(CborSpan item) {
        Set<BigInteger> labels = new HashSet<>();
        for (Map.Entry<CborSpan, CborSpan> entry : StrictCbor.map(item)) {
            BigInteger label = StrictCbor.unsigned(entry.getKey());
            if (!labels.add(label)) {
                throw new TxDecodingException("duplicate metadata label " + label);
            }
            metadatum(entry.getValue());
        }
    }

    /** {@code decodeMetadatum} with the size checks of decoder versions above 2. */
    private static void metadatum(CborSpan item) {
        CborCursor c = new CborCursor(item.buffer(), item.offset(), item.offset() + item.length());
        CborCursor.Containers open = new CborCursor.Containers();
        do {
            int initial = c.peek();
            int major = initial >>> 5;
            switch (major) {
                case 0, 1 -> c.argument(c.next());
                case 2 -> {
                    if (string(c, 2) > MAX_METADATUM_BYTES) {
                        throw new TxDecodingException("bytes .size (0..64): bytestring exceeds 64 bytes");
                    }
                }
                case 3 -> {
                    if (string(c, 3) > MAX_METADATUM_BYTES) {
                        throw new TxDecodingException("text .size (0..64): text exceeds 64 bytes");
                    }
                }
                case 4, 5 -> open.open(major, c.containerHeader(major));
                default -> throw new TxDecodingException("Unsupported token type in metadata (major type " + major
                        + ")");
            }
        } while (open.next(c));
    }

    /**
     * Reads a byte or text string, definite or indefinite; {@code decodeString} validates each text chunk as UTF-8.
     *
     * @return its length, the chunks of an indefinite one together
     */
    private static int string(CborCursor c, int major) {
        if ((c.peek() & 0x1f) != 31) {
            return chunk(c, major);
        }
        c.next();
        int length = 0;
        while (!c.atBreak()) {
            length += chunk(c, major);
        }
        return length;
    }

    private static int chunk(CborCursor c, int major) {
        byte[] chunk = c.definiteString(major);
        if (major == 3) {
            try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(chunk));
            } catch (CharacterCodingException e) {
                throw new TxDecodingException("metadata text is not valid UTF-8");
            }
        }
        return chunk.length;
    }
}
