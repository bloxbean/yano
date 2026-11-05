package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.raw.FieldSpec;
import com.bloxbean.cardano.client.transaction.raw.FieldType;
import com.bloxbean.cardano.client.transaction.raw.FormatPolicy;
import com.bloxbean.cardano.client.transaction.raw.RawHeader;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.transaction.raw.RecordSpec;
import com.bloxbean.cardano.client.util.HexUtil;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What Haskell's decoders reject in a ledger record that CCL's reader walks (ADR 0003 D6): the reader knows where each
 * field is, and this policy decides by the record's shape, the field's row and the value's type, never by a key number
 * or a position. Anything rejected is a {@link TxDecodingException} ({@code ENGINE.DecodingFailure}).
 *
 * <p>The decoder version decides three rules (cardano-ledger {@code f649f975}). Below 12 a byte string is definite
 * ({@code decodeBytes}, Decoder.hs:355-361) and a {@code RecD} record or a tuple, such as an anchor, a value's pair,
 * ex units or a redeemer's value, is a definite-length array (BaseTypes.hs:996-1004, Value.hs:298-310, ExUnits.hs:208-
 * 216, DecCBOR.hs:288-300); from 12 a {@code UnitInterval}'s numbers are
 * {@code Word64}s and no longer bignums ({@code decodeIntegralRational},
 * BaseTypes.hs:417-428). Below 9 a text has at most 64 bytes and a set no tag or rule against duplicates, which this
 * class does not hold.
 *
 * <p>A type this class does not name has no rule yet: its values are read by {@link RawTransaction}. Its rules stand
 * for a transaction at Conway's decoder versions and for a block header of any era, which no version below 12 changes.
 */
public final class HaskellPolicy implements FormatPolicy {

    /** Conway's decoder versions, 9 to 11, reject alike. */
    static final HaskellPolicy CONWAY = new HaskellPolicy(9);

    private static final int FIRST_KNOWN_VERSION = 9;
    private static final int VERSION_12 = 12;
    private static final int KEY_HASH_LENGTH = 28;
    private static final int HASH32_LENGTH = 32;
    private static final int IPV4_LENGTH = 4;
    private static final int IPV6_LENGTH = 16;
    private static final int REWARD_ACCOUNT_LENGTH = 29;
    private static final int VKEY_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;
    private static final int VRF_PROOF_LENGTH = 80;
    private static final int KES_SIGNATURE_LENGTH = 448;
    private static final int MAX_ASSET_NAME_LENGTH = 32;
    private static final int TAG_EMBEDDED = 24;
    private static final long MAX_WORD16 = 0xFFFFL;
    private static final long MAX_WORD32 = 0xFFFF_FFFFL;
    /** {@code MaxVersion}, Version.hs:63. */
    private static final long MAX_VERSION = 13;
    private static final BigInteger MIN_INT64 = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_INT64 = BigInteger.valueOf(Long.MAX_VALUE);
    private static final int MAX_PORT = 0xFFFF;
    private static final int MAX_NETWORK_ID = 1;
    private static final int MAX_KEY_SHOWN = 9;
    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_MAP = 5;
    private static final long NO_TAG = -1;
    /** Conway's {@code PlutusPurpose}, spending to proposing (Conway/Scripts.hs:292-300). */
    private static final long MAX_REDEEMER_TAG = 5;

    private final int decoderVersion;

    HaskellPolicy(int decoderVersion) {
        if (decoderVersion < FIRST_KNOWN_VERSION) {
            throw new IllegalArgumentException("no rules for decoder version " + decoderVersion);
        }
        this.decoderVersion = decoderVersion;
    }

    /** Walks a Conway transaction with this policy: its array, body, witness set, validity flag and aux data. */
    void walk(RawTx tx) {
        try {
            RawTx.walk(tx.span(), Era.Conway, this);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /** Walks one record of a Conway transaction with this policy. */
    void walk(RecordSpec record, CborSpan value) {
        try {
            record.walk(value, Era.Conway, this);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /** Walks one value of a type: its rule, then its table. */
    void walk(FieldType type, CborSpan value) {
        check(type, value, Era.Conway);
        walk(type.record(), value);
    }

    /**
     * Whether a block header decodes, in either layout. No decoder version below 12 changes a header's rules, so they
     * are read at Conway's. A header and its body may be of either length: {@code decodeRecordNamed} at the dataset's
     * cardano-ledger {@code 6176e413} (Praos/BlockHeader.hs:204-226), later than the {@code RecD} of {@code f649f975}.
     *
     * @param header a header as encoded
     * @return CCL's view of it
     * @throws TxDecodingException when Haskell would not decode it
     */
    public static RawHeader header(CborSpan header) {
        try {
            RawHeader.walk(header, CONWAY);
            return RawHeader.of(header);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /**
     * A record is in no tag, as Haskell does not look through one, or in its own: the aux data map's 259
     * ({@code TagD 259}, Alonzo/TxAuxData.hs:269-271).
     */
    @Override
    public void enter(RecordSpec record, CborSpan container, Era era) {
        CborSpan content = container;
        if (record.tag() != NO_TAG) {
            if (container.tag() != record.tag()) {
                throw new TxDecodingException(record + " at " + container.offset() + " is not in tag " + record.tag());
            }
            content = container.untag();
        }
        boolean map = record.shape() == RecordSpec.Shape.KEYED;
        if (StrictCbor.major(content) != (map ? MAJOR_MAP : MAJOR_ARRAY)) {
            throw new TxDecodingException(record + " at " + container.offset() + " is not an untagged "
                    + (map ? "map" : "array"));
        }
    }

    /**
     * No unknown field, none outside its era, none repeated ({@code invalidField}, {@code decodeRecordSum}), but a
     * redeemer's key: the map of redeemers keeps its last value ({@code Map.fromList}, Alonzo/TxWits.hs:566-573).
     */
    @Override
    public void entry(RecordSpec record, FieldSpec field, CborSpan key, CborSpan value, int occurrence, Era era) {
        if (field == null) {
            throw new TxDecodingException("unknown " + at(record, key, value));
        }
        if (!field.validIn(era)) {
            throw new TxDecodingException(at(record, key, value) + " is not in " + era);
        }
        if (occurrence > 0 && field.type() != FieldType.REDEEMER_VALUE) {
            throw new TxDecodingException("repeated " + at(record, key, value));
        }
        if (field.record() != null && isSet(field.type())) {
            distinct(StrictCbor.set(value), field.record(), era);
        }
    }

    private static boolean isSet(FieldType type) {
        return switch (type) {
            case SET, NONEMPTY_SET, NONEMPTY_CERTIFICATE_SET -> true;
            default -> false;
        };
    }

    // A key can be any item, of any size.
    private static String at(RecordSpec record, CborSpan key, CborSpan value) {
        if (key == null) {
            return record + " item at " + value.offset();
        }
        return record + " key " + (key.length() <= MAX_KEY_SHOWN ? HexUtil.encodeHexString(key.bytes())
                : "at " + key.offset());
    }

    /** A record has every field required in its era; a positional one, every item. */
    @Override
    public void missing(RecordSpec record, FieldSpec field, CborSpan container, Era era) {
        throw new TxDecodingException(record + " at " + container.offset() + " has no " + field.type() + " (no key "
                + field.key() + ")");
    }

    @Override
    public void check(FieldType type, CborSpan value, Era era) {
        switch (type) {
            case UINT, COIN, SEQUENCE_NUMBER, KES_PERIOD -> StrictCbor.unsigned(value);
            case UINT16 -> atMost(value, MAX_WORD16);
            case UINT32, MINOR_PROTOCOL_VERSION -> atMost(value, MAX_WORD32);
            case MAJOR_PROTOCOL_VERSION -> atMost(value, MAX_VERSION);
            case POSITIVE_COIN -> {
                if (StrictCbor.unsigned(value).signum() == 0) {
                    throw new TxDecodingException("a positive coin is zero at " + value.offset());
                }
            }
            case NETWORK_ID -> {
                if (StrictCbor.unsignedLong(value) > MAX_NETWORK_ID) {
                    throw new TxDecodingException("network id at " + value.offset());
                }
            }
            case HASH28 -> sized(value, KEY_HASH_LENGTH, "key hash");
            case HASH32 -> sized(value, HASH32_LENGTH, "hash");
            case OPTIONAL_HASH32 -> sizedOrNull(value, HASH32_LENGTH, "hash");
            case VKEY, VRF_VKEY, KES_VKEY -> sized(value, VKEY_LENGTH, "verification key");
            case SIGNATURE -> sized(value, SIGNATURE_LENGTH, "signature");
            case KES_SIGNATURE -> sized(value, KES_SIGNATURE_LENGTH, "KES signature");
            case VRF_PROOF -> sized(value, VRF_PROOF_LENGTH, "VRF proof");
            case VRF_CERT -> {
                if (decoderVersion < VERSION_12) {
                    definite(value, "a VRF result (enforceSize)");
                }
            }
            case ADDRESS -> AddressBytes.validate(bytes(value));
            case VALUE -> {
                if (decoderVersion < VERSION_12 && StrictCbor.major(value) == MAJOR_ARRAY) {
                    definite(value, "a multi-asset value (decodeValuePair)");
                }
            }
            case ASSET_NAME -> {
                int found = length(value);
                if (found > MAX_ASSET_NAME_LENGTH) {
                    throw new TxDecodingException("asset name of " + found + " bytes");
                }
            }
            case INT64 -> int64(value);
            case NONZERO_INT64 -> {
                if (int64(value).signum() == 0) {
                    throw new TxDecodingException("MultiAsset cannot contain zeros");
                }
            }
            case DATA -> PlutusData.validate(embedded(value, "an inline datum"));
            case SCRIPT_REF -> RawScript.readScript(StrictCbor.span(embedded(value, "a script reference")));
            case PLUTUS_DATA -> PlutusData.validate(value);
            case NATIVE_SCRIPT -> RawScript.of(RawScript.NATIVE, value);
            case PLUTUS_SCRIPT, BYTES -> length(value);
            case METADATA -> Metadata.validate(value);
            case OPTIONAL_AUXILIARY_DATA -> {
                if (decoderVersion < VERSION_12 && !value.isNull() && StrictCbor.major(value) == MAJOR_ARRAY) {
                    definite(value, "auxiliary data [metadata, scripts] (RecD)");
                }
            }
            case BOOL -> value.asBoolean();
            case REDEEMERS -> {
                if (value.isTagged()) {
                    throw new TxDecodingException("redeemers at " + value.offset() + " are in a tag");
                }
            }
            case REDEEMER_TAG -> {
                if (StrictCbor.unsignedLong(value) > MAX_REDEEMER_TAG) {
                    throw new TxDecodingException("redeemer tag " + StrictCbor.unsigned(value) + " at "
                            + value.offset());
                }
            }
            case REDEEMER_VALUE, EX_UNITS -> {
                if (decoderVersion < VERSION_12) {
                    definite(value, type + " (decodeTuple, RecD ExUnits)");
                }
            }
            // ExUnits' decNat: at most maxBound :: Int64
            case EX_UNIT -> StrictCbor.unsignedLong(value);
            case REWARD_ACCOUNT -> rewardAccount(value);
            case URL -> BoundedFields.url(value, "ledger record");
            case DNS_NAME -> BoundedFields.dnsName(value);
            case UNIT_INTERVAL -> unitInterval(value);
            case OPTIONAL_PORT -> {
                if (!value.isNull() && StrictCbor.unsignedLong(value) > MAX_PORT) {
                    throw new TxDecodingException("relay port exceeds Word16");
                }
            }
            case OPTIONAL_IPV4 -> sizedOrNull(value, IPV4_LENGTH, "IPv4 address");
            case OPTIONAL_IPV6 -> sizedOrNull(value, IPV6_LENGTH, "IPv6 address");
            case OPTIONAL_ANCHOR -> {
                if (decoderVersion < VERSION_12 && !value.isNull() && value.isIndefinite()) {
                    throw new TxDecodingException("an indefinite-length array at " + value.offset()
                            + " (an anchor is definite only below decoder version 12)");
                }
            }
            case LIST, RELAY_LIST -> StrictCbor.array(value);
            case SET -> StrictCbor.set(value);
            case NONEMPTY_LIST, NONEMPTY_SET -> nonEmpty(StrictCbor.set(value).isEmpty(), type.name());
            case NONEMPTY_MAP -> nonEmpty(StrictCbor.map(value).isEmpty(), type.name());
            case KEY_HASH_SET -> keyHashes(value);
            case NONEMPTY_CERTIFICATE_SET -> nonEmpty(StrictCbor.set(value).isEmpty(), "Certificates");
            default -> {
            }
        }
    }

    private byte[] bytes(CborSpan value) {
        return decoderVersion < VERSION_12 ? StrictCbor.definiteBytes(value) : StrictCbor.bytes(value);
    }

    private int length(CborSpan value) {
        return decoderVersion < VERSION_12 ? StrictCbor.definiteLength(value) : StrictCbor.bytes(value).length;
    }

    private void sized(CborSpan value, int length, String what) {
        int found = length(value);
        if (found != length) {
            throw new TxDecodingException(what + " of " + found + " bytes, expected " + length);
        }
    }

    /** A definite-length array, as {@code decodeListLen} reads one. */
    private static void definite(CborSpan value, String what) {
        if (value.isIndefinite()) {
            throw new TxDecodingException("an indefinite-length array at " + value.offset() + " (" + what + ")");
        }
    }

    private static void atMost(CborSpan value, long max) {
        long found = StrictCbor.unsignedLong(value);
        if (found > max) {
            throw new TxDecodingException("unsigned integer " + found + " exceeds " + max + " at " + value.offset());
        }
    }

    /** {@code decodeIntegerBounded64} (Mary/Value.hs:310-335): an integer, not a bignum, within {@code Int64}. */
    private static BigInteger int64(CborSpan value) {
        BigInteger found = StrictCbor.integer(value);
        if (found.compareTo(MIN_INT64) < 0 || found.compareTo(MAX_INT64) > 0) {
            throw new TxDecodingException("overflow when decoding mint field: " + found);
        }
        return found;
    }

    /** {@code decodeNestedCborBytes}: tag 24 on a byte string (Decoding.hs:238-239). */
    private byte[] embedded(CborSpan value, String what) {
        if (value.tag() != TAG_EMBEDDED) {
            throw new TxDecodingException(what + " at " + value.offset() + " is wrapped in tag 24");
        }
        return bytes(value.untag());
    }

    private void sizedOrNull(CborSpan value, int length, String what) {
        if (!value.isNull()) {
            sized(value, length, what);
        }
    }

    /** {@code decodeAccountAddressT} (Address.hs:938-955): header {@code & 0xEE == 0xE0}, then a 28-byte hash. */
    private void rewardAccount(CborSpan value) {
        byte[] account = bytes(value);
        if (account.length != REWARD_ACCOUNT_LENGTH || (account[0] & 0xee) != 0xe0) {
            throw new TxDecodingException("reward account is not an account address");
        }
    }

    private void unitInterval(CborSpan value) {
        BoundedFields.unitInterval(value, "unit interval");
        if (decoderVersion >= VERSION_12) {
            StrictCbor.array(value.untag()).forEach(StrictCbor::unsigned);
        }
    }

    /** A set of key hashes: {@code decodeSet} rejects a repeated one from version 9 (Decoder.hs:952-962). */
    private void keyHashes(CborSpan value) {
        Set<ByteBuffer> seen = new HashSet<>();
        for (CborSpan hash : StrictCbor.set(value)) {
            sized(hash, KEY_HASH_LENGTH, "key hash");
            if (!seen.add(ByteBuffer.wrap(bytes(hash)))) {
                throw new TxDecodingException("duplicate key hash at " + hash.offset());
            }
        }
    }

    /**
     * {@code decodeSet} and {@code decodeOSet} reject two equal items, equal as decoded values. Each item is walked
     * first: that bounds its depth, which {@link CborCanonical}'s recursion relies on.
     */
    private void distinct(List<CborSpan> items, RecordSpec record, Era era) {
        if (items.size() < 2) {
            return;
        }
        Set<ByteBuffer> seen = new HashSet<>();
        for (CborSpan item : items) {
            record.walk(item, era, this);
            if (!seen.add(EqualityKey.of(item, record, era))) {
                throw new TxDecodingException("duplicate " + record + " at " + item.offset());
            }
        }
    }

    private static void nonEmpty(boolean empty, String field) {
        if (empty) {
            throw new TxDecodingException("'" + field + "' must be non-empty when supplied");
        }
    }

    /**
     * The value a record that passed this policy decodes to: its entries' canonical encodings ({@link CborCanonical}),
     * with a set of key hashes in sorted order. An entry nested in another repeats part of it, which changes no
     * comparison.
     */
    private static final class EqualityKey implements FormatPolicy {
        private final ByteArrayOutputStream key = new ByteArrayOutputStream();

        /** @return the key of an item the policy accepted; one the walk does not enter is a leaf, its own key */
        static ByteBuffer of(CborSpan item, RecordSpec record, Era era) {
            EqualityKey walked = new EqualityKey();
            record.walk(item, era, walked);
            return ByteBuffer.wrap(walked.key.size() == 0 ? CborCanonical.of(item) : walked.key.toByteArray());
        }

        @Override
        public void entry(RecordSpec record, FieldSpec field, CborSpan tag, CborSpan value, int occurrence, Era era) {
            key.writeBytes(field.type() == FieldType.KEY_HASH_SET ? CborCanonical.set(value)
                    : CborCanonical.of(tag != null ? tag : value));
        }
    }
}
