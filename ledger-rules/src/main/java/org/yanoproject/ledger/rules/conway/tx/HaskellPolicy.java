package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.FieldSpec;
import com.bloxbean.cardano.client.transaction.raw.FieldType;
import com.bloxbean.cardano.client.transaction.raw.FormatPolicy;
import com.bloxbean.cardano.client.transaction.raw.GovActionKind;
import com.bloxbean.cardano.client.transaction.raw.RawHeader;
import com.bloxbean.cardano.client.transaction.raw.RawProtocolVersion;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.transaction.raw.RecordSpec;
import com.bloxbean.cardano.client.transaction.raw.TxBodyField;
import com.bloxbean.cardano.client.transaction.raw.WitnessSetField;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Haskell's decoders reject in a ledger record that CCL's reader walks (ADR 0003 D6): the reader knows where each
 * field is, and this policy decides by the record's shape, the field's row and the value's type, never by a key number
 * or a position. Anything rejected is a {@link TxDecodingException} ({@code ENGINE.DecodingFailure}).
 *
 * <p>The rules are those of Conway's decoder version, 9 (cardano-ledger {@code f649f975}): a byte string is definite
 * ({@code decodeBytes}, Decoder.hs:355-361) and a {@code RecD} record or a tuple, such as an anchor, a value's pair,
 * ex units, a redeemer's value, prices, a proposal, a vote, a governance action id or a constitution, is a
 * definite-length array (BaseTypes.hs:996-1004, Value.hs:298-310, ExUnits.hs:208-216, 235-243, DecCBOR.hs:288-300,
 * Procedures.hs:181-190, 487-497, 559-570, 1036-1046). Haskell decodes an era's blocks, headers and transactions with
 * {@code eraDecoder}, at the era's lowest major version ({@code eraProtVerLow}, cardano-ledger Core/Era.hs:259-260;
 * ouroboros-consensus Shelley/Ledger/Block.hs:352, Mempool.hs:294), not at the ledger's current protocol version, so a
 * Conway transaction decodes at 9 at protocol versions 9 to 11. A later era's decoder version (Dijkstra's 12) needs its
 * own policy.
 *
 * <p>A type this class does not name has no rule yet: its values are read by {@link RawTransaction}. Its rules stand
 * for a Conway transaction and for a block header of any era, which no version below 12 changes.
 */
public final class HaskellPolicy implements FormatPolicy {

    /** Conway's decoder version: {@code eraProtVerLow ConwayEra} (Era.hs:132). */
    public static final HaskellPolicy CONWAY = new HaskellPolicy();

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
    /** {@code Vote}'s {@code decodeEnumBounded}: no, yes, abstain (Procedures.hs:418-427). */
    private static final long MAX_VOTE = 2;
    /** A cost model's language is a {@code Word8} ({@code Map Word8 [Int64]}, Plutus/CostModels.hs:302-304). */
    private static final long MAX_LANGUAGE = 0xFF;
    /** {@code succVersion (ProtVerHigh ConwayEra)}, Conway's highest major version being 11 (Era.hs:133). */
    private static final long MAX_HARD_FORK_MAJOR = 12;
    /** A proposal procedure, an item of the body's set of them. */
    private static final RecordSpec PROPOSAL = TxBodyField.PROPOSAL_PROCEDURES.spec().record();
    /** A hard fork initiation, the member of the governance action union. */
    private static final RecordSpec HARD_FORK = GovActionKind.HARD_FORK_INITIATION.member().record();
    /** An output and the redeemers, whose decoders pick their form by cborg's token type. */
    private static final RecordSpec OUTPUT = TxBodyField.OUTPUTS.spec().record();
    private static final RecordSpec REDEEMERS = WitnessSetField.REDEEMERS_FIELD.spec().record();
    /** {@code isMultiAssetSmallEnough} (Mary/Value.hs:721-723): {@code 44 * assets + 28 * policies <= 65535}. */
    private static final long MAX_COMPACT_MULTI_ASSET = 65535;
    private static final long COMPACT_ASSET = 44;
    private static final long COMPACT_POLICY = 28;
    /**
     * CCL's table of a transaction without its validity flag, {@code [body, witness_set, auxiliary_data / null]}, whose
     * rows it gives to Mary and from Dijkstra ({@code RawTx}); it has no public constant.
     */
    private static final String WITHOUT_VALIDITY_FLAG = "pre_alonzo_transaction";
    /** A map's head with an 8-byte length, which cborg reports as {@code TypeMapLen64}. */
    private static final int MAP_HEAD_8_BYTES = 0xbb;

    private HaskellPolicy() {
    }

    /** Walks a Conway transaction with this policy: its array, body, witness set, validity flag and aux data. */
    void walk(RawTx tx) {
        try {
            RawTx.walk(tx.span(), Era.Conway, this);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /**
     * Walks one record of a Conway transaction with this policy: the reader of a CDDL rule that has a table.
     *
     * @throws TxDecodingException when Haskell would not decode it
     */
    public void walk(RecordSpec record, CborSpan value) {
        try {
            record.walk(value, Era.Conway, this);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /**
     * Walks one value of a type: its rule, then its table if it has one. The reader of a CDDL rule that is one type,
     * not a collection (a collection's rules, such as being non-empty, are its parent's).
     *
     * @throws RuntimeException when Haskell would not decode it, normally a {@link TxDecodingException}
     */
    public void walk(FieldType type, CborSpan value) {
        checkField(type, value, null, null);
        check(type, value, Era.Conway);
        if (type.record() != null) {
            walk(type.record(), value);
        }
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
     * ({@code TagD 259}, Alonzo/TxAuxData.hs:269-271). A proposal procedure, an item of a set, is a {@code RecD}
     * record (Procedures.hs:559-570). A multi-asset, of a value or of mint, is small enough to compact, at every
     * decoder version ({@code decodeMultiAsset}, Mary/Value.hs:327-337).
     */
    @Override
    public void enter(RecordSpec record, CborSpan container, Era era) {
        if (record == PROPOSAL) {
            definite(container, record.name() + " is a RecD record");
        }
        if (isMultiAsset(record)) {
            smallEnoughToCompact(container);
        }
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
     * A transaction may leave out its validity flag, which is then {@code True}: Conway decodes a transaction with
     * Alonzo's decoder, which peeks for a {@code TypeBool} and takes a list of 3 or 4 items (Alonzo/Tx.hs:538-553,
     * {@code decodeRecordNamed "AlonzoTx"}), as a node does a transaction submitted to its mempool. A block's
     * transactions are segregated, so this form is a submitted transaction's alone.
     *
     * <p>No unknown field, none outside its era, none repeated ({@code invalidField}, {@code decodeRecordSum},
     * {@code decodeMapByKey}), but a redeemer's key: the map of redeemers keeps its last value ({@code Map.fromList},
     * Alonzo/TxWits.hs:566-573). A hard fork's major version is at most {@code succVersion (ProtVerHigh ConwayEra)}
     * ({@code decodeProtVer @ConwayEra}, BaseTypes.hs:253-263, Procedures.hs:938-940); a header's may be one more.
     * The form a value takes, where it may take more than one (a record by major type, such as a value, an output,
     * auxiliary data or redeemers), is checked for its era only: its items are checked where its parent's entry is,
     * and a map of redeemers keeps a repeated key. An output or the redeemers, though, is a map only by a head that
     * cborg reports as {@code TypeMapLen} or {@code TypeMapLenIndef}: the 8-byte head, {@code TypeMapLen64}, takes the
     * list form's decoder, which fails (Babbage/TxOut.hs:554-556, Alonzo/TxWits.hs:556-562).
     */
    @Override
    public void entry(RecordSpec record, FieldSpec field, CborSpan key, CborSpan value, int occurrence, Era era) {
        if (field == null) {
            throw new TxDecodingException("unknown " + at(record, key, value));
        }
        if (!field.validIn(era) && !record.name().equals(WITHOUT_VALIDITY_FLAG)) {
            throw new TxDecodingException(at(record, key, value) + " is not in " + era);
        }
        if (record.shape() == RecordSpec.Shape.BY_MAJOR_TYPE) {
            boolean dispatchedByToken = record == OUTPUT || record == REDEEMERS;
            if (dispatchedByToken && (value.buffer()[value.offset()] & 0xff) == MAP_HEAD_8_BYTES) {
                throw new TxDecodingException(record + " at " + value.offset() + " is a map with an 8-byte head");
            }
            checkField(field.type(), value, record, null);
            return;
        }
        if (occurrence > 0 && field.type() != FieldType.REDEEMER_VALUE) {
            throw new TxDecodingException("repeated " + at(record, key, value));
        }
        checkField(field.type(), value, record, key);
        if (field.record() != null && isSet(field.type())) {
            distinct(StrictCbor.set(value), field.record(), era);
        } else if (field.record() != null && hasRecordKeys(field.record())) {
            distinct(StrictCbor.map(value).stream().map(Map.Entry::getKey).toList(), field.record().keyType().record(),
                    era);
        }
        if (record == HARD_FORK && field.type() == FieldType.PROTOCOL_VERSION) {
            int major = RawProtocolVersion.of(value).major();
            if (major > MAX_HARD_FORK_MAJOR) {
                throw new TxDecodingException("Protocol version " + major + " exceeds the maximum expected version of "
                        + MAX_HARD_FORK_MAJOR);
            }
        }
    }

    /** A {@code multiasset<a>}: policy ids to maps of asset names to amounts. */
    private static boolean isMultiAsset(RecordSpec record) {
        if (record.keyType() != FieldType.HASH28 || record.fields().size() != 1) {
            return false;
        }
        RecordSpec assets = record.fields().getFirst().record();
        return assets != null && assets.keyType() == FieldType.ASSET_NAME;
    }

    private static void smallEnoughToCompact(CborSpan multiAsset) {
        List<Map.Entry<CborSpan, CborSpan>> policies = StrictCbor.map(multiAsset);
        long assets = 0;
        for (Map.Entry<CborSpan, CborSpan> policy : policies) {
            assets += StrictCbor.map(policy.getValue()).size();
        }
        if (COMPACT_ASSET * assets + COMPACT_POLICY * policies.size() > MAX_COMPACT_MULTI_ASSET) {
            throw new TxDecodingException("MultiAsset is too big to compact: " + assets + " assets under "
                    + policies.size() + " policies");
        }
    }

    // A map whose keys are records, such as voters: CCL counts a repeated key by its items' bytes.
    private static boolean hasRecordKeys(RecordSpec map) {
        return map.keyType() != null && map.keyType().record() != null;
    }

    private static boolean isSet(FieldType type) {
        return switch (type) {
            case SET, NONEMPTY_SET, NONEMPTY_CERTIFICATE_SET, CREDENTIAL_SET -> true;
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
            case UINT, COIN, SEQUENCE_NUMBER, KES_PERIOD -> StrictCbor.word64(value);
            case UINT16 -> atMost(value, MAX_WORD16);
            case UINT32, MINOR_PROTOCOL_VERSION -> atMost(value, MAX_WORD32);
            case MAJOR_PROTOCOL_VERSION -> atMost(value, MAX_VERSION);
            case POSITIVE_COIN -> {
                if (StrictCbor.word64(value) == 0) {
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
            case VRF_CERT -> definite(value, "a VRF result (enforceSize)");
            case ADDRESS -> AddressBytes.validate(StrictCbor.definiteBytes(value));
            case VALUE -> {
                if (StrictCbor.major(value) == MAJOR_ARRAY) {
                    definite(value, "a multi-asset value (decodeValuePair)");
                }
            }
            case ASSET_NAME -> {
                int found = StrictCbor.definiteLength(value);
                if (found > MAX_ASSET_NAME_LENGTH) {
                    throw new TxDecodingException("asset name of " + found + " bytes");
                }
            }
            case INT64 -> int64(value, "mint field");
            case NONZERO_INT64 -> {
                if (int64(value, "mint field").signum() == 0) {
                    throw new TxDecodingException("MultiAsset cannot contain zeros");
                }
            }
            case DATA -> PlutusData.validate(embedded(value, "an inline datum"));
            // Conway's native scripts have no guard (RawScript.requireConwayTypes)
            case SCRIPT_REF -> RawScript.readScript(StrictCbor.span(embedded(value, "a script reference")))
                    .requireConwayTypes();
            case PLUTUS_DATA -> PlutusData.validate(value);
            case NATIVE_SCRIPT -> RawScript.of(RawScript.NATIVE, value).requireConwayTypes();
            case PLUTUS_SCRIPT, BYTES -> StrictCbor.definiteLength(value);
            case METADATA -> Metadata.validate(value);
            case OPTIONAL_AUXILIARY_DATA -> {
                if (!value.isNull() && StrictCbor.major(value) == MAJOR_ARRAY) {
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
            case REDEEMER_VALUE, EX_UNITS, EX_UNIT_PRICES, ANCHOR, OPTIONAL_ANCHOR, GOV_ACTION_ID,
                 OPTIONAL_GOV_ACTION_ID, VOTING_PROCEDURE, CONSTITUTION -> {
                if (!value.isNull()) {
                    definite(value, type.name() + " is a RecD record");
                }
            }
            // ExUnits' decNat: at most maxBound :: Int64
            case EX_UNIT -> StrictCbor.unsignedLong(value);
            case REWARD_ACCOUNT -> rewardAccount(value);
            case DNS_NAME -> BoundedFields.dnsName(value);
            case UNIT_INTERVAL -> rational(value, true);
            case NONNEGATIVE_INTERVAL -> rational(value, false);
            case OPTIONAL_HASH28 -> sizedOrNull(value, KEY_HASH_LENGTH, "script hash");
            case VOTE -> {
                if (StrictCbor.unsignedLong(value) > MAX_VOTE) {
                    throw new TxDecodingException("unknown vote " + StrictCbor.unsigned(value) + " at "
                            + value.offset());
                }
            }
            case LANGUAGE -> atMost(value, MAX_LANGUAGE);
            case COST_MODEL -> StrictCbor.array(value).forEach(cost -> int64(cost, "cost model"));
            case OPTIONAL_PORT -> {
                if (!value.isNull() && StrictCbor.unsignedLong(value) > MAX_PORT) {
                    throw new TxDecodingException("relay port exceeds Word16");
                }
            }
            case OPTIONAL_IPV4 -> sizedOrNull(value, IPV4_LENGTH, "IPv4 address");
            case OPTIONAL_IPV6 -> sizedOrNull(value, IPV6_LENGTH, "IPv6 address");
            case LIST, RELAY_LIST -> StrictCbor.array(value);
            case SET, CREDENTIAL_SET -> StrictCbor.set(value);
            case KEY_HASH_SET -> keyHashes(value);
            default -> {
            }
        }
    }

    /**
     * The rules of a type whose rejection names its field ({@link #field}): a {@code Url}'s, and a collection's that
     * must not be empty.
     *
     * @param record the record the value is in, or null for a value read alone
     * @param key    its key in the record, or null
     */
    private static void checkField(FieldType type, CborSpan value, RecordSpec record, CborSpan key) {
        boolean empty;
        switch (type) {
            case URL -> {
                BoundedFields.url(value, () -> field(type, value, record, key));
                return;
            }
            case NONEMPTY_LIST, NONEMPTY_SET, NONEMPTY_CERTIFICATE_SET -> empty = StrictCbor.set(value).isEmpty();
            case NONEMPTY_ARRAY -> empty = StrictCbor.array(value).isEmpty();
            case NONEMPTY_MAP -> empty = StrictCbor.map(value).isEmpty();
            default -> {
                return;
            }
        }
        if (empty) {
            throw new TxDecodingException(field(type, value, record, key) + ": the " + type + " at " + value.offset()
                    + " must be non-empty when supplied");
        }
    }

    /** @return the field a value is: the record and key it is under, or its type when it is read alone */
    private static String field(FieldType type, CborSpan value, RecordSpec record, CborSpan key) {
        return record == null ? type.name() : at(record, key, value);
    }

    private static void sized(CborSpan value, int length, String what) {
        int found = StrictCbor.definiteLength(value);
        if (found != length) {
            throw new TxDecodingException(what + " of " + found + " bytes, expected " + length);
        }
    }

    /** A definite-length array, as {@code decodeListLen} reads one: a {@code RecD} record or a tuple. */
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

    /**
     * {@code decodeIntegerBounded64} (Mary/Value.hs:310-335) and a cost model's {@code Int64}: an integer, not a
     * bignum, within {@code Int64}.
     */
    private static BigInteger int64(CborSpan value, String what) {
        BigInteger found = StrictCbor.integer(value);
        if (found.compareTo(MIN_INT64) < 0 || found.compareTo(MAX_INT64) > 0) {
            throw new TxDecodingException("overflow when decoding " + what + " at " + value.offset() + ": " + found);
        }
        return found;
    }

    /** {@code decodeNestedCborBytes}: tag 24 on a byte string (Decoding.hs:238-239). */
    private static byte[] embedded(CborSpan value, String what) {
        if (value.tag() != TAG_EMBEDDED) {
            throw new TxDecodingException(what + " at " + value.offset() + " is wrapped in tag 24");
        }
        return StrictCbor.definiteBytes(value.untag());
    }

    private static void sizedOrNull(CborSpan value, int length, String what) {
        if (!value.isNull()) {
            sized(value, length, what);
        }
    }

    /** {@code decodeAccountAddressT} (Address.hs:938-955): header {@code & 0xEE == 0xE0}, then a 28-byte hash. */
    private static void rewardAccount(CborSpan value) {
        byte[] account = StrictCbor.definiteBytes(value);
        if (account.length != REWARD_ACCOUNT_LENGTH || (account[0] & 0xee) != 0xe0) {
            throw new TxDecodingException("reward account is not an account address");
        }
    }

    /** A {@code UnitInterval} or a {@code NonNegativeInterval} ({@link BoundedFields#boundedRational}). */
    private static void rational(CborSpan value, boolean unit) {
        BoundedFields.boundedRational(value, unit ? "unit interval" : "non-negative interval", unit);
    }

    /** A set of key hashes: {@code decodeSet} rejects a repeated one from version 9 (Decoder.hs:952-962). */
    private static void keyHashes(CborSpan value) {
        Set<ByteBuffer> seen = new HashSet<>();
        for (CborSpan hash : StrictCbor.set(value)) {
            sized(hash, KEY_HASH_LENGTH, "key hash");
            if (!seen.add(ByteBuffer.wrap(StrictCbor.definiteBytes(hash)))) {
                throw new TxDecodingException("duplicate key hash at " + hash.offset());
            }
        }
    }

    /**
     * {@code decodeSet} and {@code decodeOSet} reject two equal items, and {@code decodeMap} two equal keys, equal as
     * decoded values. The walk checks each item afterwards; an item it would reject has a key all the same, or is too
     * deep for one.
     */
    private static void distinct(List<CborSpan> items, RecordSpec record, Era era) {
        if (items.size() < 2) {
            return;
        }
        Set<ByteBuffer> seen = new HashSet<>();
        for (CborSpan item : items) {
            if (!seen.add(EqualityKey.of(item, record, era))) {
                throw new TxDecodingException("duplicate " + record + " at " + item.offset());
            }
        }
    }

    /**
     * The value a record that passes this policy decodes to: its canonical encoding ({@link CborCanonical}), with the
     * sets its table names sorted, tagged or not.
     */
    private static final class EqualityKey implements FormatPolicy {
        private final Set<Integer> sets = new HashSet<>();

        /** @return the key of an item; one the walk does not enter is a leaf, its own key */
        static ByteBuffer of(CborSpan item, RecordSpec record, Era era) {
            if (ofLeaves(record)) {
                return ByteBuffer.wrap(CborCanonical.of(item));
            }
            EqualityKey walked = new EqualityKey();
            record.walk(item, era, walked);
            return ByteBuffer.wrap(CborCanonical.of(item, walked.sets));
        }

        // A record of leaves, none a set, decodes to equal values exactly when it has one canonical encoding.
        private static boolean ofLeaves(RecordSpec record) {
            for (FieldSpec field : record.fields()) {
                if ((field.record() != null && !ofLeaves(field.record())) || isSetOf(field.type())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isSetOf(FieldType type) {
            return type == FieldType.KEY_HASH_SET || isSet(type);
        }

        @Override
        public void check(FieldType type, CborSpan value, Era era) {
            if (isSetOf(type)) {
                sets.add(value.offset());
            }
        }
    }
}
