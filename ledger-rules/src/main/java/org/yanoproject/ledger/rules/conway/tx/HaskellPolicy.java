package org.yanoproject.ledger.rules.conway.tx;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.raw.FieldSpec;
import com.bloxbean.cardano.client.transaction.raw.FieldType;
import com.bloxbean.cardano.client.transaction.raw.FormatPolicy;
import com.bloxbean.cardano.client.transaction.raw.RecordSpec;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What Haskell's decoders reject in a ledger record that CCL's reader walks (ADR 0003 D6): the reader knows where each
 * field is, and this policy decides by the record's shape, the field's row and the value's type, never by a key number
 * or a position. Anything rejected is a {@link TxDecodingException} ({@code ENGINE.DecodingFailure}).
 *
 * <p>The decoder version decides three rules (cardano-ledger {@code f649f975}). Below 12 a byte string is definite
 * ({@code decodeBytes}, Decoder.hs:355-361) and an anchor is a definite-length {@code RecD} (BaseTypes.hs:996-1004);
 * from 12 a {@code UnitInterval}'s numbers are {@code Word64}s and no longer bignums ({@code decodeIntegralRational},
 * BaseTypes.hs:417-428). Below 9 a text has at most 64 bytes and a set no tag, which this class does not hold.
 *
 * <p>A type this class does not name has no rule yet: its values are read by {@link RawTransaction}.
 */
final class HaskellPolicy implements FormatPolicy {

    /** Conway's decoder versions, 9 to 11, reject alike. */
    static final HaskellPolicy CONWAY = new HaskellPolicy(9);

    private static final int FIRST_KNOWN_VERSION = 9;
    private static final int VERSION_12 = 12;
    private static final int KEY_HASH_LENGTH = 28;
    private static final int HASH32_LENGTH = 32;
    private static final int IPV4_LENGTH = 4;
    private static final int IPV6_LENGTH = 16;
    private static final int REWARD_ACCOUNT_LENGTH = 29;
    private static final int MAX_PORT = 0xFFFF;
    private static final int MAX_NETWORK_ID = 1;
    private static final int MAX_KEY_SHOWN = 9;
    private static final int MAJOR_ARRAY = 4;
    private static final int MAJOR_MAP = 5;

    private final int decoderVersion;

    HaskellPolicy(int decoderVersion) {
        if (decoderVersion < FIRST_KNOWN_VERSION) {
            throw new IllegalArgumentException("no rules for decoder version " + decoderVersion);
        }
        this.decoderVersion = decoderVersion;
    }

    /** Walks one record of a Conway transaction with this policy. */
    void walk(RecordSpec record, CborSpan value) {
        try {
            record.walk(value, Era.Conway, this);
        } catch (CborRuntimeException e) {
            throw new TxDecodingException(e.getMessage(), e);
        }
    }

    /** A record is never tagged: Haskell does not look through a tag. */
    @Override
    public void enter(RecordSpec record, CborSpan container, Era era) {
        boolean map = record.shape() == RecordSpec.Shape.KEYED;
        if (StrictCbor.major(container) != (map ? MAJOR_MAP : MAJOR_ARRAY)) {
            throw new TxDecodingException(record + " at " + container.offset() + " is not an untagged "
                    + (map ? "map" : "array"));
        }
    }

    /** No unknown field, none outside its era, none repeated ({@code invalidField}, {@code decodeRecordSum}). */
    @Override
    public void entry(RecordSpec record, FieldSpec field, CborSpan key, CborSpan value, int occurrence, Era era) {
        String at = record + (key != null ? " key " + describe(key) : " item at " + value.offset());
        if (field == null) {
            throw new TxDecodingException("unknown " + at);
        }
        if (!field.validIn(era)) {
            throw new TxDecodingException(at + " is not in " + era);
        }
        if (occurrence > 0) {
            throw new TxDecodingException("repeated " + at);
        }
    }

    // A key can be any item, of any size.
    private static String describe(CborSpan key) {
        return key.length() <= MAX_KEY_SHOWN ? HexUtil.encodeHexString(key.bytes()) : "at " + key.offset();
    }

    /** A record has every field required in its era; a positional one, every item. */
    @Override
    public void missing(RecordSpec record, FieldSpec field, CborSpan container, Era era) {
        throw new TxDecodingException(record + " at " + container.offset() + " has no " + field.type());
    }

    @Override
    public void check(FieldType type, CborSpan value, Era era) {
        switch (type) {
            case UINT, COIN -> StrictCbor.unsigned(value);
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
            case BYTES -> bytes(value);
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
            case KEY_HASH_SET -> {
                for (CborSpan hash : StrictCbor.set(value)) {
                    sized(hash, KEY_HASH_LENGTH, "key hash");
                }
            }
            case NONEMPTY_CERTIFICATE_SET -> certificates(value, era);
            default -> {
            }
        }
    }

    private byte[] bytes(CborSpan value) {
        return decoderVersion < VERSION_12 ? StrictCbor.definiteBytes(value) : StrictCbor.bytes(value);
    }

    private void sized(CborSpan value, int length, String what) {
        int found = bytes(value).length;
        if (found != length) {
            throw new TxDecodingException(what + " of " + found + " bytes, expected " + length);
        }
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

    /**
     * An {@code OSet}: not empty, and {@code decodeOSet} rejects two equal certificates, equal as decoded values. Each
     * is walked first: that bounds its depth, which {@link CborCanonical}'s recursion relies on.
     */
    private void certificates(CborSpan value, Era era) {
        List<CborSpan> items = StrictCbor.set(value);
        nonEmpty(items.isEmpty(), "Certificates");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            CertKind.RECORD.walk(items.get(i), era, this);
            EqualityKey key = new EqualityKey();
            CertKind.RECORD.walk(items.get(i), era, key);
            if (!seen.add(key.toString())) {
                throw new TxDecodingException("duplicate certificate " + i);
            }
        }
    }

    private static void nonEmpty(boolean empty, String field) {
        if (empty) {
            throw new TxDecodingException("TxBody: '" + field + "' must be non-empty when supplied");
        }
    }

    /**
     * The value a record that passed this policy decodes to, as a string: its entries' canonical encodings
     * ({@link CborCanonical}), with a set of key hashes in sorted order. An entry nested in another repeats part of it,
     * which changes no comparison.
     */
    private static final class EqualityKey implements FormatPolicy {
        private final StringBuilder key = new StringBuilder();

        @Override
        public void entry(RecordSpec record, FieldSpec field, CborSpan tag, CborSpan value, int occurrence, Era era) {
            byte[] canonical = field.type() == FieldType.KEY_HASH_SET ? CborCanonical.set(value)
                    : CborCanonical.of(tag != null ? tag : value);
            key.append(HexUtil.encodeHexString(canonical)).append('|');
        }

        @Override
        public String toString() {
            return key.toString();
        }
    }
}
