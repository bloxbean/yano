package org.yanoproject.ledger.rules.conway.tx;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * ({@code validateAlonzoTxAuxData}, :360-373) is that every Plutus script here is well formed, so the Plutus scripts
 * are kept.</p>
 */
public final class RawAuxData {

    private static final int AUX_DATA_TAG = 259;
    private static final int MAX_METADATUM_BYTES = 64;

    private final List<RawScript> plutusScripts;

    private RawAuxData(List<RawScript> plutusScripts) {
        this.plutusScripts = List.copyOf(plutusScripts);
    }

    /** @return the Plutus scripts carried in the auxiliary data, in encoded order */
    public List<RawScript> plutusScripts() {
        return plutusScripts;
    }

    /**
     * @param bytes the auxiliary data's original bytes
     * @throws TxDecodingException when Haskell would not decode them
     */
    public static RawAuxData decode(byte[] bytes) {
        CborReader reader = new CborReader(bytes);
        List<RawScript> plutus = new ArrayList<>();
        switch (reader.peekMajor()) {
            case 5 -> readMetadata(reader);
            case 4 -> {
                long length = reader.readArrayHeader();
                if (length != 2 && length != CborReader.INDEFINITE) {
                    throw new TxDecodingException("Allegra auxiliary data has 2 elements, found " + length);
                }
                readMetadata(reader);
                readNativeScripts(reader);
                if (length == CborReader.INDEFINITE && reader.hasNext(length, 2)) {
                    throw new TxDecodingException("Allegra auxiliary data has 2 elements");
                }
            }
            case 6 -> {
                if (reader.readTag() != AUX_DATA_TAG) {
                    throw new TxDecodingException("Alonzo auxiliary data carries tag 259");
                }
                long entries = reader.readMapHeader();
                Set<Long> seen = new HashSet<>();
                for (long i = 0; reader.hasNext(entries, i); i++) {
                    long key = reader.readUnsignedLong();
                    if (!seen.add(key)) {
                        throw new TxDecodingException("Duplicate key: " + key + " in auxiliary data");
                    }
                    switch ((int) Math.min(key, 6)) {
                        case 0 -> readMetadata(reader);
                        case 1 -> readNativeScripts(reader);
                        case 2, 3, 4 -> {
                            long count = reader.readArrayHeader();
                            for (long n = 0; reader.hasNext(count, n); n++) {
                                plutus.add(new RawScript((int) key - 1, reader.readDefiniteBytes()));
                            }
                        }
                        case 5 -> throw new TxDecodingException("PlutusV4 is not supported in Conway");
                        default -> throw new TxDecodingException("unknown auxiliary data key " + key);
                    }
                }
            }
            default -> throw new TxDecodingException("Failed to decode AlonzoTxAuxData");
        }
        if (!reader.atEnd()) {
            throw new TxDecodingException("trailing bytes after the auxiliary data");
        }
        return new RawAuxData(plutus);
    }

    /** {@code Map Word64 Metadatum}. */
    private static void readMetadata(CborReader reader) {
        long entries = reader.readMapHeader();
        Set<BigInteger> labels = new HashSet<>();
        for (long i = 0; reader.hasNext(entries, i); i++) {
            BigInteger label = reader.readUnsigned();
            if (!labels.add(label)) {
                throw new TxDecodingException("duplicate metadata label " + label);
            }
            readMetadatum(reader);
        }
    }

    private static void readNativeScripts(CborReader reader) {
        long count = reader.readArrayHeader();
        for (long n = 0; reader.hasNext(count, n); n++) {
            Timelock.read(reader);
        }
    }

    /** {@code decodeMetadatum} with the size checks of decoder versions above 2. */
    private static void readMetadatum(CborReader reader) {
        int major = reader.peekMajor();
        switch (major) {
            case 0, 1 -> reader.readInteger();
            case 2 -> {
                if (reader.readBytes().length > MAX_METADATUM_BYTES) {
                    throw new TxDecodingException("bytes .size (0..64): bytestring exceeds 64 bytes");
                }
            }
            case 3 -> {
                // decodeString validates each chunk as UTF-8; the size check is on the concatenation.
                int length = 0;
                for (byte[] chunk : reader.readTextChunks()) {
                    try {
                        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(chunk));
                    } catch (CharacterCodingException e) {
                        throw new TxDecodingException("metadata text is not valid UTF-8");
                    }
                    length += chunk.length;
                }
                if (length > MAX_METADATUM_BYTES) {
                    throw new TxDecodingException("text .size (0..64): text exceeds 64 bytes");
                }
            }
            case 4 -> {
                long count = reader.readArrayHeader();
                for (long i = 0; reader.hasNext(count, i); i++) {
                    readMetadatum(reader);
                }
            }
            case 5 -> {
                long count = reader.readMapHeader();
                for (long i = 0; reader.hasNext(count, i); i++) {
                    readMetadatum(reader);
                    readMetadatum(reader);
                }
            }
            default -> throw new TxDecodingException("Unsupported token type in metadata (major type " + major + ")");
        }
    }
}
